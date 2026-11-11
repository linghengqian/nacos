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

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Oracle as the datasource: configurations, their history, gray configurations, namespaces and the
 * paging of lists, which the mappers of the Oracle datasource plugin turn into SQL, through the
 * driver that the build took from its plugin directory. The driver is not part of the Server; the
 * datasource and the platform of the build are structural choices.
 *
 * <p>The Server runs without authentication. The default auth plugin cannot use Oracle: the
 * schema of the repository does not create its table of permissions (the column resource is a
 * reserved word of Oracle), and the order of the column clauses of the other tables is one that
 * Oracle rejects, which the database of the test corrects before it applies the script
 * ({@link NativeDatabase}). Those are alibaba/nacos#15978 and #15979.
 *
 * @author Nacos
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NativeOracleITCase {

    private static final String GROUP = "DEFAULT_GROUP";

    private static final Pattern PAGE_ITEM = Pattern.compile("\"dataId\"\\s*:\\s*\"([^\"]*)\"");

    private final NativeHttp http = new NativeHttp();

    private NativeEnvironment environment;

    private String api;

    @BeforeAll
    void startEnvironment() {
        environment = NativeEnvironment.builder("oracle").platform("oracle").bootstrap(false)
                .plugin(driverJar())
                .property("nacos.core.auth.enabled", "false")
                .property("nacos.core.auth.admin.enabled", "false")
                .property("nacos.core.auth.console.enabled", "false")
                .build().start();
        api = environment.server().apiUrl() + "/nacos/v3";
    }

    @AfterAll
    void stopEnvironment() {
        if (environment != null) {
            environment.close();
        }
    }

    @Test
    void configIsStoredUpdatedReadAndDeleted() {
        String dataId = "oracle-" + UUID.randomUUID();
        assertTrue(publish(dataId, GROUP, "", "first").ok(), "publish");
        NativeHttp.Response detail = detail(dataId, GROUP, "");
        assertTrue(detail.ok() && detail.matches("\"content\"\\s*:\\s*\"first\""),
                detail.describe());

        assertTrue(publish(dataId, GROUP, "", "second").ok(), "update");
        detail = detail(dataId, GROUP, "");
        assertTrue(detail.ok() && detail.matches("\"content\"\\s*:\\s*\"second\""),
                detail.describe());

        // The open API answers from what the Server has dumped from the database.
        NativeHttp.Response served = eventually(
                () -> http.get(api + "/client/cs/config?" + query(dataId, GROUP, ""), null),
                each -> each.ok() && each.matches("\"content\"\\s*:\\s*\"second\""));
        assertTrue(served.ok() && served.matches("\"content\"\\s*:\\s*\"second\""),
                served.describe());

        NativeHttp.Response history = http.get(api + "/admin/cs/history/list?"
                + query(dataId, GROUP, "") + "&pageNo=1&pageSize=10", null);
        assertTrue(history.ok(), history.describe());
        assertEquals(2, count(history.body(), dataId), history.describe());

        assertTrue(delete("/admin/cs/config", query(dataId, GROUP, "")).ok(), "delete");
        assertFalse(detail(dataId, GROUP, "").ok(), "the configuration is gone");
    }

    @Test
    void grayConfigIsStoredAndDeleted() {
        String dataId = "oracle-gray-" + UUID.randomUUID();
        String gray = "tagv2_native_oracle";
        NativeHttp.Response published = http.form("POST", api + "/admin/cs/config/gray", null,
                Map.of("dataId", dataId, "groupName", GROUP, "content", "gray", "grayName", gray,
                        "grayRuleExp", "region=hz", "grayVersion", "1.0.0"));
        assertTrue(published.ok(), published.describe());

        NativeHttp.Response stored = http.get(api + "/admin/cs/config/gray?"
                + query(dataId, GROUP, "") + "&grayName=" + encode(gray), null);
        assertTrue(stored.ok() && stored.matches("\"grayName\"\\s*:\\s*\"" + gray + "\""),
                stored.describe());

        NativeHttp.Response removed = delete("/admin/cs/config/gray",
                query(dataId, GROUP, "") + "&grayName=" + encode(gray));
        assertTrue(removed.ok(), removed.describe());
    }

    @Test
    void listIsPaged() {
        String group = "oracle-paging-" + UUID.randomUUID().toString().substring(0, 8);
        String prefix = "paged-";
        int total = 12;
        try {
            for (int index = 0; index < total; index++) {
                assertTrue(publish(prefix + index, group, "", "content-" + index).ok(),
                        "publish " + index);
            }
            assertEquals(5, page(group, 1, 5, prefix), "first page");
            assertEquals(5, page(group, 2, 5, prefix), "second page");
            assertEquals(2, page(group, 3, 5, prefix), "third page");
            NativeHttp.Response first = http.get(listUrl(group, 1, 5), null);
            assertTrue(first.matches("\"totalCount\"\\s*:\\s*" + total), first.describe());
        } finally {
            for (int index = 0; index < total; index++) {
                delete("/admin/cs/config", query(prefix + index, group, ""));
            }
        }
    }

    @Test
    void namespaceIsCreatedUpdatedAndDeleted() {
        String namespace = "oracle-ns-" + UUID.randomUUID().toString().substring(0, 8);
        String dataId = "in-namespace-" + UUID.randomUUID();
        NativeHttp.Response created = http.form("POST", api + "/admin/core/namespace", null,
                Map.of("namespaceId", namespace, "namespaceName", "first name",
                        "namespaceDesc", "created by the Oracle test"));
        assertTrue(created.ok(), created.describe());

        NativeHttp.Response listed = http.get(api + "/admin/core/namespace/list", null);
        assertTrue(listed.ok() && listed.matches("\"namespace\"\\s*:\\s*\"" + namespace + "\""),
                listed.describe());

        assertTrue(publish(dataId, GROUP, namespace, "content").ok(), "publish in the namespace");
        NativeHttp.Response inNamespace = detail(dataId, GROUP, namespace);
        assertTrue(inNamespace.ok() && inNamespace.matches("\"content\"\\s*:\\s*\"content\""),
                inNamespace.describe());

        NativeHttp.Response updated = http.form("PUT", api + "/admin/core/namespace", null,
                Map.of("namespaceId", namespace, "namespaceName", "second name",
                        "namespaceDesc", "updated by the Oracle test"));
        assertTrue(updated.ok(), updated.describe());
        NativeHttp.Response renamed = http.get(api + "/admin/core/namespace?namespaceId="
                + encode(namespace), null);
        assertTrue(renamed.ok() && renamed.matches("second name"), renamed.describe());

        assertTrue(delete("/admin/cs/config", query(dataId, GROUP, namespace)).ok(),
                "delete the configuration");
        NativeHttp.Response removed = delete("/admin/core/namespace",
                "namespaceId=" + encode(namespace));
        assertTrue(removed.ok(), removed.describe());
        assertFalse(http.get(api + "/admin/core/namespace?namespaceId=" + encode(namespace), null)
                .ok(), "the namespace is gone");
    }

    /**
     * The jar of the driver, which the ordinary Server loads from its plugin directory. A Native
     * executable has the driver of its build, which {@code native-ci.sh} takes from the same
     * version.
     */
    private static Path driverJar() {
        try {
            return Path.of(oracle.jdbc.OracleDriver.class.getProtectionDomain().getCodeSource()
                    .getLocation().toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    private NativeHttp.Response publish(String dataId, String group, String namespace,
            String content) {
        return http.form("POST", api + "/admin/cs/config", null,
                namespace.isEmpty()
                        ? Map.of("dataId", dataId, "groupName", group, "content", content,
                                "type", "text")
                        : Map.of("dataId", dataId, "groupName", group, "content", content,
                                "type", "text", "namespaceId", namespace));
    }

    private NativeHttp.Response detail(String dataId, String group, String namespace) {
        return http.get(api + "/admin/cs/config?" + query(dataId, group, namespace), null);
    }

    private NativeHttp.Response delete(String path, String query) {
        return http.form("DELETE", api + path + '?' + query, null, Map.of());
    }

    private String listUrl(String group, int pageNo, int pageSize) {
        return api + "/admin/cs/config/list?groupName=" + encode(group) + "&search=accurate"
                + "&pageNo=" + pageNo + "&pageSize=" + pageSize;
    }

    private int page(String group, int pageNo, int pageSize, String prefix) {
        NativeHttp.Response response = http.get(listUrl(group, pageNo, pageSize), null);
        assertTrue(response.ok(), response.describe());
        return count(response.body(), prefix);
    }

    private static int count(String body, String dataIdPrefix) {
        Matcher matcher = PAGE_ITEM.matcher(body);
        int count = 0;
        while (matcher.find()) {
            if (matcher.group(1).startsWith(dataIdPrefix)) {
                count++;
            }
        }
        return count;
    }

    private static String query(String dataId, String group, String namespace) {
        String query = "dataId=" + encode(dataId) + "&groupName=" + encode(group);
        return namespace.isEmpty() ? query : query + "&namespaceId=" + encode(namespace);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static NativeHttp.Response eventually(Supplier<NativeHttp.Response> request,
            Predicate<NativeHttp.Response> done) {
        NativeHttp.Response response = request.get();
        for (int attempt = 0; attempt < 20 && !done.test(response); attempt++) {
            sleep(500);
            response = request.get();
        }
        return response;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
