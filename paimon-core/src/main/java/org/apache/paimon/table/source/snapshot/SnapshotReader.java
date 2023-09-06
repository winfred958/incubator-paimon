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

package org.apache.paimon.table.source.snapshot;

import org.apache.paimon.Snapshot;
import org.apache.paimon.consumer.ConsumerManager;
import org.apache.paimon.data.BinaryRow;
import org.apache.paimon.predicate.Predicate;
import org.apache.paimon.table.source.DataSplit;
import org.apache.paimon.table.source.RichPlan;
import org.apache.paimon.table.source.ScanMode;
import org.apache.paimon.table.source.Split;
import org.apache.paimon.table.source.SplitGenerator;
import org.apache.paimon.utils.Filter;
import org.apache.paimon.utils.SnapshotManager;

import java.util.List;

/** Read splits from specified {@link Snapshot} with given configuration. */
public interface SnapshotReader {

    SnapshotManager snapshotManager();

    ConsumerManager consumerManager();

    SplitGenerator splitGenerator();

    SnapshotReader withSnapshot(long snapshotId);

    SnapshotReader withSnapshot(Snapshot snapshot);

    SnapshotReader withFilter(Predicate predicate);

    SnapshotReader withMode(ScanMode scanMode);

    SnapshotReader withLevelFilter(Filter<Integer> levelFilter);

    SnapshotReader withBucket(int bucket);

    SnapshotReader withBucketFilter(Filter<Integer> bucketFilter);

    /** Get splits plan from snapshot. */
    Plan read();

    /** Get splits plan from an overwritten snapshot. */
    Plan readOverwrittenChanges();

    Plan readIncrementalDiff(Snapshot before);

    /** Get partitions from a snapshot. */
    List<BinaryRow> partitions();

    /** Result plan of this scan. */
    interface Plan extends RichPlan {

        /** Result splits. */
        List<Split> splits();

        default List<DataSplit> dataSplits() {
            return (List) splits();
        }
    }
}
