/*
 * Copyright 1999-2026 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.alibaba.nacos.test.nativeimage;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Data that has to survive a restart of the Server. The integration suites never restart the
 * Server and create only a few kinds of data, so this check covers what they cannot reach:
 *
 * <ul>
 *   <li>a persistent service with a label selector and a persistent instance in a cluster with the
 *   MYSQL health checker. The Raft groups of the naming service write them into their log and into
 *   their snapshots and read both back when the Server starts. The Server answers the service
 *   detail with the selector and the checker, which Jackson writes.</li>
 *   <li>a naming switch that the Raft group replicates. The suites set switches in memory only.</li>
 *   <li>a configuration, which the datasource keeps.</li>
 * </ul>
 *
 * <p>The restart has to load the data from snapshots, not replay it from the Raft log, because
 * loading a snapshot is the code that the check exists to reach. Reading the data back proves
 * neither, so the check looks at the snapshots themselves. Before the restart it waits until the
 * newest snapshot of every Raft group that the seed wrote holds the seed, which the environment lets
 * JRaft take every 30 seconds. After the restart it looks in the logs for each of these groups
 * loading exactly that snapshot, and for the persistent service among the services that the
 * persistent naming group read from it. A snapshot that was taken before the seed, or none, fails
 * the check, as does a restart that replays the log.
 *
 * <p>The check does not throw for a failed step: it returns one line per step, {@code PASS} or
 * {@code FAIL}, as {@code native-ci.sh} reported them.
 *
 * @author Nacos
 */
public final class NativeRestartCheck {

    private static final String GROUP = "DEFAULT_GROUP";

    private static final String SERVICE = "native-restart-service";

    private static final String CLUSTER = "DB";

    private static final String DATA_ID = "native-restart-config";

    private static final String SWITCH_ENTRY = "defaultCacheMillis";

    private static final String SWITCH_VALUE = "12345";

    private static final String SWITCH_DEFAULT = "3000";

    private static final String SERVICE_QUERY = "serviceName=" + SERVICE + "&groupName=" + GROUP
            + "&namespaceId=public";

    private static final String LABEL_SELECTOR = "\"labels\"\\s*:\\s*\\[\\s*\"zone\"\\s*\\]";

    private static final String MYSQL_CHECKER = "\"type\"\\s*:\\s*\"MYSQL\"";

    /**
     * The Raft groups that the seed writes, each with a text that the snapshot of the group holds
     * once it holds the seed: the persistent instance, the metadata of the service and the switch.
     * The groups of the instance metadata and of the locks get nothing from the seed.
     */
    private static final Map<String, String> SEEDED_GROUPS = new LinkedHashMap<>();

    static {
        SEEDED_GROUPS.put("naming_persistent_service_v2", SERVICE);
        SEEDED_GROUPS.put("naming_service_metadata", SERVICE);
        SEEDED_GROUPS.put("naming_persistent_service", "\"" + SWITCH_ENTRY + "\":" + SWITCH_VALUE);
    }

    private final NativeHttp http = new NativeHttp();

    private final NativeEnvironment environment;

    private final List<String> report = new ArrayList<>();

    private final String api;

    private final String probeIp;

    private final String probePort;

    private final String probeUser;

    private final String probePwd;

    private NativeRestartCheck(NativeEnvironment environment) {
        this.environment = environment;
        this.api = environment.server().apiUrl() + "/nacos/v3";
        // When the datasource is MySQL, probe the datasource itself, so the success path of the
        // health checker runs. Otherwise nothing listens on the probed port, which is picked at
        // runtime, and only the failure path runs.
        boolean mysqlDatasource = "mysql".equals(environment.database().platform());
        this.probeIp = mysqlDatasource ? environment.database().host() : "127.0.0.1";
        this.probePort = mysqlDatasource ? String.valueOf(environment.database().mappedPort())
                : String.valueOf(NativeServer.freePort());
        this.probeUser = mysqlDatasource ? "nacos" : "probe";
        this.probePwd = mysqlDatasource ? "nacos" : "secret";
    }

    /**
     * Seeds the data, restarts the Server of the environment, and looks for the data.
     *
     * @param environment a started environment
     * @return the report, one line per step
     */
    public static List<String> run(NativeEnvironment environment) {
        NativeRestartCheck check = new NativeRestartCheck(environment);
        check.execute();
        return check.report;
    }

    private void execute() {
        NativeServer server = environment.server();
        String token = environment.identities().adminToken();
        cleanup(token);
        List<String> failures = seed(token);
        if (!failures.isEmpty()) {
            record(false, "seed: " + String.join("; ", failures));
            return;
        }
        record(true, "seed");

        // JRaft takes a snapshot of each group every 30 seconds here, and none of them has to hold
        // the seed: one that was taken before it lacks it. Wait until the newest snapshot of every
        // group that the seed wrote holds it, so that the restart has to load the seed.
        NativeRaftSnapshots snapshots = new NativeRaftSnapshots(server.raftDirectory());
        List<String> without = awaitSnapshots(snapshots);
        if (!without.isEmpty()) {
            record(false, "snapshots saved after the seed: no snapshot holds the seed of "
                    + String.join(", ", without));
            return;
        }

        if (server.stop()) {
            record(true, "stop");
        } else {
            record(false, "stop: the Server ignored SIGTERM for 60 seconds");
        }
        // The Server is down, so no snapshot is taken any more: these are the ones that it loads.
        Map<String, Long> expected = newestIndexes(snapshots);
        record(true, "snapshots saved after the seed: " + describe(expected, "snapshot_"));
        String logsBefore = server.allLogs();
        try {
            server.restart();
        } catch (RuntimeException e) {
            record(false, "start after the restart: " + e.getMessage());
            return;
        }
        record(true, "start after the restart");

        failures = verifyRecovery(logsBefore, server.allLogs(), expected);
        if (failures.isEmpty()) {
            record(true, "snapshots loaded by the restart: " + describe(expected, "snapshot_"));
        } else {
            record(false, "snapshots loaded by the restart: " + String.join("; ", failures));
        }

        String restarted = environment.identities().adminToken();
        failures = verify(restarted);
        cleanup(restarted);
        if (failures.isEmpty()) {
            record(true, "data after the restart");
        } else {
            record(false, "data after the restart: " + String.join("; ", failures));
        }
    }

    private List<String> seed(String token) {
        List<String> failures = new ArrayList<>();
        require(failures, "persistent service with a label selector", form("POST",
                "/admin/ns/service", token, Map.of("serviceName", SERVICE, "groupName", GROUP,
                        "namespaceId", "public", "ephemeral", "false", "protectThreshold", "0.5",
                        "metadata", "{\"owner\":\"team-x\"}", "selector",
                        "{\"type\":\"label\",\"expression\":\"CONSUMER.label.zone = "
                                + "PROVIDER.label.zone\"}")));
        require(failures, "persistent instance", form("POST", "/admin/ns/instance", token,
                Map.of("serviceName", SERVICE, "groupName", GROUP, "namespaceId", "public", "ip",
                        probeIp, "port", probePort, "clusterName", CLUSTER, "weight", "2.5",
                        "ephemeral", "false", "metadata", "{\"zone\":\"z1\"}")));
        // The cluster exists once the instance is visible.
        for (int attempt = 0; attempt < 40; attempt++) {
            if (get("/admin/ns/instance/list?" + SERVICE_QUERY + "&clusterName=" + CLUSTER, token)
                    .body().contains(probePort)) {
                break;
            }
            sleep(500);
        }
        require(failures, "cluster with the MYSQL health checker", form("PUT", "/admin/ns/cluster",
                token, Map.of("serviceName", SERVICE, "groupName", GROUP, "namespaceId", "public",
                        "clusterName", CLUSTER, "checkPort", probePort,
                        "useInstancePort4Check", "true", "healthChecker",
                        "{\"type\":\"MYSQL\",\"user\":\"" + probeUser + "\",\"pwd\":\"" + probePwd
                                + "\"," + "\"cmd\":\"select 1\"}", "metadata", "{\"owner\":\"team-x\"}")));
        // debug=false replicates the switch through the Raft group instead of keeping it in memory.
        require(failures, "naming switch through the Raft group", form("PUT",
                "/admin/ns/ops/switches", token,
                Map.of("entry", SWITCH_ENTRY, "value", SWITCH_VALUE, "debug", "false")));
        require(failures, "config publish", form("POST", "/admin/cs/config", token,
                Map.of("dataId", DATA_ID, "groupName", GROUP, "content", "restart=true", "type",
                        "properties")));

        // What the Server answers now is what it has to answer after the restart.
        NativeHttp.Response detail = get("/admin/ns/service?" + SERVICE_QUERY, token);
        require(failures, "service detail before the restart", detail);
        expect(failures, "label selector before the restart", detail, LABEL_SELECTOR);
        expect(failures, "MYSQL health checker before the restart", detail, MYSQL_CHECKER);
        return failures;
    }

    private List<String> verify(String token) {
        List<String> failures = new ArrayList<>();
        NativeHttp.Response detail = get("/admin/ns/service?" + SERVICE_QUERY, token);
        require(failures, "service detail after the restart", detail);
        expect(failures, "label selector after the restart", detail, LABEL_SELECTOR);
        expect(failures, "MYSQL health checker after the restart", detail, MYSQL_CHECKER);

        NativeHttp.Response instances = get("/admin/ns/instance/list?" + SERVICE_QUERY
                + "&clusterName=" + CLUSTER, token);
        require(failures, "instance list after the restart", instances);
        expect(failures, "persistent instance after the restart", instances, probePort);
        expect(failures, "instance metadata after the restart", instances,
                "\"zone\"\\s*:\\s*\"z1\"");

        NativeHttp.Response switches = get("/admin/ns/ops/switches", token);
        require(failures, "switches after the restart", switches);
        expect(failures, "replicated switch after the restart", switches,
                "\"" + SWITCH_ENTRY + "\"\\s*:\\s*" + SWITCH_VALUE);

        NativeHttp.Response config = get("/admin/cs/config?dataId=" + DATA_ID + "&groupName="
                + GROUP, token);
        require(failures, "config get after the restart", config);
        expect(failures, "config content after the restart", config, "restart=true");
        return failures;
    }

    /**
     * Best effort: it removes what an earlier run left and what this run created.
     */
    private void cleanup(String token) {
        form("DELETE", "/admin/ns/instance?" + SERVICE_QUERY + "&ip=" + probeIp + "&port=" + probePort
                + "&clusterName=" + CLUSTER + "&ephemeral=false", token, Map.of());
        form("DELETE", "/admin/ns/service?" + SERVICE_QUERY, token, Map.of());
        form("DELETE", "/admin/cs/config?dataId=" + DATA_ID + "&groupName=" + GROUP, token,
                Map.of());
        form("PUT", "/admin/ns/ops/switches", token,
                Map.of("entry", SWITCH_ENTRY, "value", SWITCH_DEFAULT, "debug", "false"));
    }

    private void record(boolean passed, String step) {
        String line = (passed ? "PASS  " : "FAIL  ") + step;
        report.add(line);
        System.out.println(line);
    }

    private NativeHttp.Response get(String path, String token) {
        return http.get(api + path, token);
    }

    private NativeHttp.Response form(String method, String path, String token,
            Map<String, String> form) {
        return http.form(method, api + path, token, form);
    }

    private static void require(List<String> failures, String name, NativeHttp.Response response) {
        if (!response.ok()) {
            failures.add(name + ": " + response.describe());
        }
    }

    private static void expect(List<String> failures, String name, NativeHttp.Response response,
            String regex) {
        if (!response.matches(regex)) {
            failures.add(name + ": the response does not match " + regex);
        }
    }

    /**
     * Waits until the newest snapshot of every group that the seed wrote holds the seed. A snapshot
     * directory exists only once its snapshot is complete, and the content tells whether it was taken
     * after the seed, so this depends neither on when JRaft takes its snapshots nor on what the logs
     * say about them.
     *
     * @param snapshots the snapshots of the Server
     * @return the groups whose newest snapshot does not hold the seed at the end, empty if all do
     */
    private static List<String> awaitSnapshots(NativeRaftSnapshots snapshots) {
        long deadline = System.nanoTime()
                + NativeTestSettings.scaled(Duration.ofMinutes(3)).toNanos();
        List<String> without = new ArrayList<>();
        while (true) {
            without.clear();
            for (Map.Entry<String, String> group : SEEDED_GROUPS.entrySet()) {
                if (!snapshots.holds(group.getKey(), group.getValue())) {
                    without.add(group.getKey());
                }
            }
            if (without.isEmpty() || System.nanoTime() >= deadline) {
                return without;
            }
            sleep(2000);
        }
    }

    private static Map<String, Long> newestIndexes(NativeRaftSnapshots snapshots) {
        Map<String, Long> indexes = new LinkedHashMap<>();
        for (String group : SEEDED_GROUPS.keySet()) {
            indexes.put(group, snapshots.newestIndex(group).orElse(-1));
        }
        return indexes;
    }

    private static String describe(Map<String, Long> indexes, String prefix) {
        List<String> parts = new ArrayList<>();
        indexes.forEach((group, index) -> parts.add(group + "=" + prefix + index));
        return String.join(", ", parts);
    }

    /**
     * Looks in the logs for what the restart loaded. The groups log {@code onSnapshotLoadDone} with
     * the index of the snapshot that they loaded, and the persistent naming group logs each service
     * that it reads from its snapshot; a replayed log entry is logged by none of them.
     *
     * @param logsBefore the logs before the restart
     * @param logsAfter  the logs after it
     * @param expected   the index of the newest snapshot of each group, which the group has to load
     * @return what is missing, empty if every group loaded its newest snapshot
     */
    private static List<String> verifyRecovery(String logsBefore, String logsAfter,
            Map<String, Long> expected) {
        List<String> failures = new ArrayList<>();
        Map<String, List<Long>> before = NativeRaftSnapshots.loaded(logsBefore);
        Map<String, List<Long>> after = NativeRaftSnapshots.loaded(logsAfter);
        for (Map.Entry<String, Long> group : expected.entrySet()) {
            List<Long> loadedBefore = before.getOrDefault(group.getKey(), List.of());
            List<Long> loadedAfter = after.getOrDefault(group.getKey(), List.of());
            List<Long> loaded = loadedAfter.subList(Math.min(loadedBefore.size(), loadedAfter.size()),
                    loadedAfter.size());
            if (!loaded.contains(group.getValue())) {
                failures.add(group.getKey() + " did not load snapshot_" + group.getValue()
                        + (loaded.isEmpty() ? ", it loaded no snapshot" : ", it loaded " + loaded));
            }
        }
        if (NativeRaftSnapshots.servicesLoaded(logsAfter, SERVICE)
                <= NativeRaftSnapshots.servicesLoaded(logsBefore, SERVICE)) {
            failures.add("naming_persistent_service_v2 did not read the service " + SERVICE
                    + " from its snapshot");
        }
        return failures;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
