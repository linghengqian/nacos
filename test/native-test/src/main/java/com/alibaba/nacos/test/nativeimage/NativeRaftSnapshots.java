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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The snapshots that the Raft groups of a Server keep on disk, and what the log says about loading
 * them. A restart that reads its data back may have replayed the Raft log instead of loading a
 * snapshot, so the restart check looks at both: the content of the newest snapshot of a group before
 * the restart, and the log line of the group that loads exactly that snapshot after it.
 *
 * <p>JRaft keeps a snapshot of a group as {@code <raft>/<group>/snapshot/snapshot_<index>}, and the
 * directory exists only after the snapshot was written and synced completely: a snapshot that fails
 * stays in {@code temp} or is deleted. Log lines say less: Nacos logs {@code SAVE cost time} when a
 * save failed as well, and JRaft discards a snapshot that holds nothing new.
 *
 * @author Nacos
 */
final class NativeRaftSnapshots {

    private static final Pattern DIRECTORY = Pattern.compile("snapshot_(\\d+)");

    /**
     * {@code Node <naming_service_metadata/10.1.0.237:25656> onSnapshotLoadDone,
     * last_included_index: 49}: JRaft INFO line of a group that loaded a snapshot.
     */
    private static final Pattern LOADED = Pattern.compile(
            "Node <([^/>]+)/[^>]*> onSnapshotLoadDone, last_included_index: (\\d+)");

    private final Path raft;

    NativeRaftSnapshots(Path raft) {
        this.raft = raft;
    }

    /**
     * The index of the newest snapshot of a group.
     *
     * @param group the Raft group
     * @return the index, empty if the group has no snapshot (yet)
     */
    OptionalLong newestIndex(String group) {
        Path directory = raft.resolve(group).resolve("snapshot");
        if (!Files.isDirectory(directory)) {
            return OptionalLong.empty();
        }
        try (Stream<Path> children = Files.list(directory)) {
            return children.map(child -> DIRECTORY.matcher(child.getFileName().toString()))
                    .filter(Matcher::matches).mapToLong(matcher -> Long.parseLong(matcher.group(1)))
                    .max();
        } catch (IOException e) {
            // JRaft deletes the older snapshot while this looks: the caller looks again.
            return OptionalLong.empty();
        }
    }

    /**
     * Whether the newest snapshot of a group holds a text. A snapshot is a directory with one zip
     * of the data of the group; the content is serialized, but the names and values of the entries
     * that the check writes stay readable in it.
     *
     * @param group  the Raft group
     * @param marker the text
     * @return true if the zip of the newest snapshot contains the text
     */
    boolean holds(String group, String marker) {
        OptionalLong index = newestIndex(group);
        if (index.isEmpty()) {
            return false;
        }
        Path directory = raft.resolve(group).resolve("snapshot").resolve("snapshot_" + index.getAsLong());
        byte[] needle = marker.getBytes(StandardCharsets.UTF_8);
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : (Iterable<Path>) files.filter(path -> path.toString().endsWith(".zip"))::iterator) {
                if (zipContains(file, needle)) {
                    return true;
                }
            }
        } catch (IOException e) {
            // The snapshot was replaced while this looked: the caller looks again.
            return false;
        }
        return false;
    }

    private static boolean zipContains(Path file, byte[] needle) throws IOException {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            for (ZipEntry entry : Collections.list(zip.entries())) {
                if (entry.isDirectory()) {
                    continue;
                }
                try (InputStream in = zip.getInputStream(entry)) {
                    if (contains(in.readAllBytes(), needle)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    static boolean contains(byte[] haystack, byte[] needle) {
        for (int start = 0; start + needle.length <= haystack.length; start++) {
            int offset = 0;
            while (offset < needle.length && haystack[start + offset] == needle[offset]) {
                offset++;
            }
            if (offset == needle.length) {
                return true;
            }
        }
        return false;
    }

    /**
     * The snapshots that the groups loaded, as the log of a Server reports them. The logs only
     * grow, so the loads of a restart are the ones behind those that the logs held before it.
     *
     * @param logs the text of the logs
     * @return the indexes of the loaded snapshots by group, in the order of the log
     */
    static Map<String, List<Long>> loaded(String logs) {
        Map<String, List<Long>> result = new HashMap<>();
        Matcher matcher = LOADED.matcher(logs);
        while (matcher.find()) {
            result.computeIfAbsent(matcher.group(1), key -> new ArrayList<>())
                    .add(Long.parseLong(matcher.group(2)));
        }
        return result;
    }

    /**
     * How many persistent services the log reports as read from a snapshot, by name. Nacos logs
     * {@code [SNAPSHOT-DATA-ADD] service=Service{..., name='x', ...}} for each one while a Server
     * loads the snapshot of the persistent naming group, and for nothing it replays from the log.
     *
     * @param logs    the text of the logs
     * @param service the name of the service
     * @return the number of lines about the service
     */
    static long servicesLoaded(String logs, String service) {
        String name = "name='" + service + "'";
        return logs.lines().filter(line -> line.contains("[SNAPSHOT-DATA-ADD]") && line.contains(name))
                .count();
    }
}
