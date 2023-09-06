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

package org.apache.paimon;

import org.apache.paimon.index.IndexFileHandler;
import org.apache.paimon.manifest.ManifestCacheFilter;
import org.apache.paimon.operation.FileStoreCommit;
import org.apache.paimon.operation.FileStoreExpire;
import org.apache.paimon.operation.FileStoreRead;
import org.apache.paimon.operation.FileStoreScan;
import org.apache.paimon.operation.FileStoreWrite;
import org.apache.paimon.operation.PartitionExpire;
import org.apache.paimon.operation.SnapshotDeletion;
import org.apache.paimon.operation.TagDeletion;
import org.apache.paimon.table.BucketMode;
import org.apache.paimon.tag.TagAutoCreation;
import org.apache.paimon.types.RowType;
import org.apache.paimon.utils.SnapshotManager;
import org.apache.paimon.utils.TagManager;

import javax.annotation.Nullable;

import java.io.Serializable;

/**
 * File store interface.
 *
 * @param <T> type of record to read and write.
 */
public interface FileStore<T> extends Serializable {

    SnapshotManager snapshotManager();

    RowType partitionType();

    CoreOptions options();

    BucketMode bucketMode();

    FileStoreScan newScan();

    IndexFileHandler newIndexFileHandler();

    FileStoreRead<T> newRead();

    FileStoreWrite<T> newWrite(String commitUser);

    FileStoreWrite<T> newWrite(String commitUser, ManifestCacheFilter manifestFilter);

    FileStoreCommit newCommit(String commitUser);

    FileStoreExpire newExpire();

    SnapshotDeletion newSnapshotDeletion();

    TagManager newTagManager();

    TagDeletion newTagDeletion();

    @Nullable
    PartitionExpire newPartitionExpire(String commitUser);

    @Nullable
    TagAutoCreation newTagCreationManager();

    boolean mergeSchema(RowType rowType, boolean allowExplicitCast);
}
