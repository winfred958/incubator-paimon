/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.paimon.operation;

import org.apache.paimon.Snapshot;
import org.apache.paimon.manifest.ManifestCommittable;
import org.apache.paimon.table.sink.CommitMessage;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Commit operation which provides commit and overwrite. */
public interface FileStoreCommit {

    /** With global lock. */
    FileStoreCommit withLock(Lock lock);

    FileStoreCommit ignoreEmptyCommit(boolean ignoreEmptyCommit);

    /** Find out which manifest committable need to be retried when recovering from the failure. */
    default List<ManifestCommittable> filterCommitted(List<ManifestCommittable> committableList) {
        Set<Long> identifiers =
                filterCommitted(
                        committableList.stream()
                                .map(ManifestCommittable::identifier)
                                .collect(Collectors.toSet()));
        return committableList.stream()
                .filter(m -> identifiers.contains(m.identifier()))
                .collect(Collectors.toList());
    }

    /** Find out which commit identifier need to be retried when recovering from the failure. */
    Set<Long> filterCommitted(Set<Long> commitIdentifiers);

    /** Commit from manifest committable. */
    void commit(ManifestCommittable committable, Map<String, String> properties);

    /**
     * Overwrite from manifest committable and partition.
     *
     * @param partition A single partition maps each partition key to a partition value. Depending
     *     on the user-defined statement, the partition might not include all partition keys. Also
     *     note that this partition does not necessarily equal to the partitions of the newly added
     *     key-values. This is just the partition to be cleaned up.
     */
    void overwrite(
            Map<String, String> partition,
            ManifestCommittable committable,
            Map<String, String> properties);

    /**
     * Drop multiple partitions. The {@link Snapshot.CommitKind} of generated snapshot is {@link
     * Snapshot.CommitKind#OVERWRITE}.
     *
     * @param partitions A list of partition {@link Map}s. NOTE: cannot be empty!
     */
    void dropPartitions(List<Map<String, String>> partitions, long commitIdentifier);

    void purgeTable(long commitIdentifier);

    /** Abort an unsuccessful commit. The data files will be deleted. */
    void abort(List<CommitMessage> commitMessages);
}
