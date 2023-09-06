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

import org.apache.paimon.CoreOptions;
import org.apache.paimon.Snapshot;
import org.apache.paimon.table.source.ScanMode;
import org.apache.paimon.utils.SnapshotManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;

/**
 * {@link StartingScanner} for the {@link CoreOptions.StartupMode#FROM_TIMESTAMP} startup mode of a
 * batch read.
 */
public class StaticFromTimestampStartingScanner implements StartingScanner {

    private static final Logger LOG =
            LoggerFactory.getLogger(StaticFromTimestampStartingScanner.class);

    private final long startupMillis;

    public StaticFromTimestampStartingScanner(long startupMillis) {
        this.startupMillis = startupMillis;
    }

    @Override
    public Result scan(SnapshotManager snapshotManager, SnapshotReader snapshotReader) {
        Snapshot startingSnapshot = timeTravelToTimestamp(snapshotManager, startupMillis);
        if (startingSnapshot == null) {
            LOG.debug(
                    "There is currently no snapshot earlier than or equal to timestamp[{}]",
                    startupMillis);
            return new NoSnapshot();
        }
        return StartingScanner.fromPlan(
                snapshotReader.withMode(ScanMode.ALL).withSnapshot(startingSnapshot.id()).read());
    }

    @Nullable
    public static Snapshot timeTravelToTimestamp(SnapshotManager snapshotManager, long timestamp) {
        return snapshotManager.earlierOrEqualTimeMills(timestamp);
    }
}
