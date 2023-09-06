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
import org.apache.paimon.data.BinaryRow;
import org.apache.paimon.data.InternalRow;
import org.apache.paimon.manifest.ManifestCacheFilter;
import org.apache.paimon.manifest.ManifestEntry;
import org.apache.paimon.manifest.ManifestEntrySerializer;
import org.apache.paimon.manifest.ManifestFile;
import org.apache.paimon.manifest.ManifestFileMeta;
import org.apache.paimon.manifest.ManifestList;
import org.apache.paimon.predicate.Predicate;
import org.apache.paimon.predicate.PredicateBuilder;
import org.apache.paimon.schema.SchemaManager;
import org.apache.paimon.schema.TableSchema;
import org.apache.paimon.stats.FieldStatsArraySerializer;
import org.apache.paimon.table.source.ScanMode;
import org.apache.paimon.types.RowType;
import org.apache.paimon.utils.FileStorePathFactory;
import org.apache.paimon.utils.Filter;
import org.apache.paimon.utils.Pair;
import org.apache.paimon.utils.ParallellyExecuteUtils;
import org.apache.paimon.utils.RowDataToObjectArrayConverter;
import org.apache.paimon.utils.SnapshotManager;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.apache.paimon.utils.Preconditions.checkArgument;
import static org.apache.paimon.utils.Preconditions.checkState;

/** Default implementation of {@link FileStoreScan}. */
public abstract class AbstractFileStoreScan implements FileStoreScan {

    private final FieldStatsArraySerializer partitionStatsConverter;
    private final RowDataToObjectArrayConverter partitionConverter;
    private final SnapshotManager snapshotManager;
    private final ManifestFile.Factory manifestFileFactory;
    private final ManifestList manifestList;
    private final int numOfBuckets;
    private final boolean checkNumOfBuckets;

    private final ConcurrentMap<Long, TableSchema> tableSchemas;
    private final SchemaManager schemaManager;
    protected final ScanBucketFilter bucketKeyFilter;

    private Predicate partitionFilter;
    private Snapshot specifiedSnapshot = null;
    private Filter<Integer> bucketFilter = null;
    private List<ManifestFileMeta> specifiedManifests = null;
    private ScanMode scanMode = ScanMode.ALL;
    private Filter<Integer> levelFilter = null;

    private ManifestCacheFilter manifestCacheFilter = null;
    private final Integer scanManifestParallelism;

    public AbstractFileStoreScan(
            RowType partitionType,
            ScanBucketFilter bucketKeyFilter,
            SnapshotManager snapshotManager,
            SchemaManager schemaManager,
            ManifestFile.Factory manifestFileFactory,
            ManifestList.Factory manifestListFactory,
            int numOfBuckets,
            boolean checkNumOfBuckets,
            Integer scanManifestParallelism) {
        this.partitionStatsConverter = new FieldStatsArraySerializer(partitionType);
        this.partitionConverter = new RowDataToObjectArrayConverter(partitionType);
        this.bucketKeyFilter = bucketKeyFilter;
        this.snapshotManager = snapshotManager;
        this.schemaManager = schemaManager;
        this.manifestFileFactory = manifestFileFactory;
        this.manifestList = manifestListFactory.create();
        this.numOfBuckets = numOfBuckets;
        this.checkNumOfBuckets = checkNumOfBuckets;
        this.tableSchemas = new ConcurrentHashMap<>();
        this.scanManifestParallelism = scanManifestParallelism;
    }

    @Override
    public FileStoreScan withPartitionFilter(Predicate predicate) {
        this.partitionFilter = predicate;
        return this;
    }

    @Override
    public FileStoreScan withPartitionFilter(List<BinaryRow> partitions) {
        List<Predicate> predicates =
                partitions.stream()
                        .filter(p -> p.getFieldCount() > 0)
                        .map(partitionConverter::createEqualPredicate)
                        .collect(Collectors.toList());
        if (predicates.isEmpty()) {
            return this;
        } else {
            return withPartitionFilter(PredicateBuilder.or(predicates));
        }
    }

    @Override
    public FileStoreScan withBucket(int bucket) {
        this.bucketFilter = i -> i == bucket;
        return this;
    }

    @Override
    public FileStoreScan withBucketFilter(Filter<Integer> bucketFilter) {
        this.bucketFilter = bucketFilter;
        return this;
    }

    @Override
    public FileStoreScan withPartitionBucket(BinaryRow partition, int bucket) {
        if (manifestCacheFilter != null) {
            checkArgument(
                    manifestCacheFilter.test(partition, bucket),
                    String.format(
                            "This is a bug! The partition %s and bucket %s is filtered!",
                            partition, bucket));
        }
        withPartitionFilter(Collections.singletonList(partition));
        withBucket(bucket);
        return this;
    }

    @Override
    public FileStoreScan withSnapshot(long snapshotId) {
        checkState(specifiedManifests == null, "Cannot set both snapshot and manifests.");
        this.specifiedSnapshot = snapshotManager.snapshot(snapshotId);
        return this;
    }

    @Override
    public FileStoreScan withSnapshot(Snapshot snapshot) {
        checkState(specifiedManifests == null, "Cannot set both snapshot and manifests.");
        this.specifiedSnapshot = snapshot;
        return this;
    }

    @Override
    public FileStoreScan withManifestList(List<ManifestFileMeta> manifests) {
        checkState(specifiedSnapshot == null, "Cannot set both snapshot and manifests.");
        this.specifiedManifests = manifests;
        return this;
    }

    @Override
    public FileStoreScan withKind(ScanMode scanMode) {
        this.scanMode = scanMode;
        return this;
    }

    @Override
    public FileStoreScan withLevelFilter(Filter<Integer> levelFilter) {
        this.levelFilter = levelFilter;
        return this;
    }

    @Override
    public FileStoreScan withManifestCacheFilter(ManifestCacheFilter manifestFilter) {
        this.manifestCacheFilter = manifestFilter;
        return this;
    }

    @Override
    public Plan plan() {

        Pair<Snapshot, List<ManifestEntry>> planResult = doPlan(this::readManifestFileMeta);

        final Snapshot readSnapshot = planResult.getLeft();
        final List<ManifestEntry> files = planResult.getRight();

        return new Plan() {
            @Nullable
            @Override
            public Long watermark() {
                return readSnapshot == null ? null : readSnapshot.watermark();
            }

            @Nullable
            @Override
            public Long snapshotId() {
                return readSnapshot == null ? null : readSnapshot.id();
            }

            @Override
            public ScanMode scanMode() {
                return scanMode;
            }

            @Override
            public List<ManifestEntry> files() {
                return files;
            }
        };
    }

    private Pair<Snapshot, List<ManifestEntry>> doPlan(
            Function<ManifestFileMeta, List<ManifestEntry>> readManifest) {
        List<ManifestFileMeta> manifests = specifiedManifests;
        Snapshot snapshot = null;
        if (manifests == null) {
            snapshot =
                    specifiedSnapshot == null
                            ? snapshotManager.latestSnapshot()
                            : specifiedSnapshot;
            if (snapshot == null) {
                manifests = Collections.emptyList();
            } else {
                manifests = readManifests(snapshot);
            }
        }

        Iterable<ManifestEntry> entries =
                ParallellyExecuteUtils.parallelismBatchIterable(
                        files ->
                                files.parallelStream()
                                        .filter(this::filterManifestFileMeta)
                                        .flatMap(m -> readManifest.apply(m).stream())
                                        .filter(this::filterByStats)
                                        .collect(Collectors.toList()),
                        manifests,
                        scanManifestParallelism);

        List<ManifestEntry> files = new ArrayList<>();
        for (ManifestEntry file : ManifestEntry.mergeEntries(entries)) {
            if (checkNumOfBuckets && file.totalBuckets() != numOfBuckets) {
                String partInfo =
                        partitionConverter.getArity() > 0
                                ? "partition "
                                        + FileStorePathFactory.getPartitionComputer(
                                                        partitionConverter.rowType(),
                                                        FileStorePathFactory.PARTITION_DEFAULT_NAME
                                                                .defaultValue())
                                                .generatePartValues(file.partition())
                                : "table";
                throw new RuntimeException(
                        String.format(
                                "Try to write %s with a new bucket num %d, but the previous bucket num is %d. "
                                        + "Please switch to batch mode, and perform INSERT OVERWRITE to rescale current data layout first.",
                                partInfo, numOfBuckets, file.totalBuckets()));
            }

            // bucket filter should not be applied along with partition filter
            // because the specifiedBucket is computed against the current
            // numOfBuckets
            // however entry.bucket() was computed against the old numOfBuckets
            // and thus the filtered manifest entries might be empty
            // which renders the bucket check invalid
            if (filterByBucket(file) && filterByBucketSelector(file) && filterByLevel(file)) {
                files.add(file);
            }
        }
        return Pair.of(snapshot, files);
    }

    private List<ManifestFileMeta> readManifests(Snapshot snapshot) {
        switch (scanMode) {
            case ALL:
                return snapshot.dataManifests(manifestList);
            case DELTA:
                return snapshot.deltaManifests(manifestList);
            case CHANGELOG:
                if (snapshot.version() > Snapshot.TABLE_STORE_02_VERSION) {
                    return snapshot.changelogManifests(manifestList);
                }

                // compatible with Paimon 0.2, we'll read extraFiles in DataFileMeta
                // see comments on DataFileMeta#extraFiles
                if (snapshot.commitKind() == Snapshot.CommitKind.APPEND) {
                    return snapshot.deltaManifests(manifestList);
                }
                throw new IllegalStateException(
                        String.format(
                                "Incremental scan does not accept %s snapshot",
                                snapshot.commitKind()));
            default:
                throw new UnsupportedOperationException("Unknown scan kind " + scanMode.name());
        }
    }

    // ------------------------------------------------------------------------
    // Start Thread Safe Methods: The following methods need to be thread safe because they will be
    // called by multiple threads
    // ------------------------------------------------------------------------

    /** Note: Keep this thread-safe. */
    protected TableSchema scanTableSchema(long id) {
        return tableSchemas.computeIfAbsent(id, key -> schemaManager.schema(id));
    }

    /** Note: Keep this thread-safe. */
    private boolean filterManifestFileMeta(ManifestFileMeta manifest) {
        return partitionFilter == null
                || partitionFilter.test(
                        manifest.numAddedFiles() + manifest.numDeletedFiles(),
                        manifest.partitionStats().fields(partitionStatsConverter));
    }

    /** Note: Keep this thread-safe. */
    private boolean filterByBucket(ManifestEntry entry) {
        return (bucketFilter == null || bucketFilter.test(entry.bucket()));
    }

    /** Note: Keep this thread-safe. */
    private boolean filterByBucketSelector(ManifestEntry entry) {
        return bucketKeyFilter.select(entry.bucket(), entry.totalBuckets());
    }

    /** Note: Keep this thread-safe. */
    private boolean filterByLevel(ManifestEntry entry) {
        return (levelFilter == null || levelFilter.test(entry.file().level()));
    }

    /** Note: Keep this thread-safe. */
    protected abstract boolean filterByStats(ManifestEntry entry);

    /** Note: Keep this thread-safe. */
    private List<ManifestEntry> readManifestFileMeta(ManifestFileMeta manifest) {
        return manifestFileFactory
                .create()
                .read(manifest.fileName(), manifestCacheRowFilter(), manifestEntryRowFilter());
    }

    /** Note: Keep this thread-safe. */
    private Filter<InternalRow> manifestEntryRowFilter() {
        Function<InternalRow, BinaryRow> partitionGetter =
                ManifestEntrySerializer.partitionGetter();
        Function<InternalRow, Integer> bucketGetter = ManifestEntrySerializer.bucketGetter();
        Function<InternalRow, Integer> totalBucketGetter =
                ManifestEntrySerializer.totalBucketGetter();
        return row -> {
            if ((partitionFilter != null
                    && !partitionFilter.test(
                            partitionConverter.convert(partitionGetter.apply(row))))) {
                return false;
            }

            if (bucketFilter != null && numOfBuckets == totalBucketGetter.apply(row)) {
                return bucketFilter.test(bucketGetter.apply(row));
            }

            return true;
        };
    }

    /** Note: Keep this thread-safe. */
    private Filter<InternalRow> manifestCacheRowFilter() {
        if (manifestCacheFilter == null) {
            return Filter.alwaysTrue();
        }

        Function<InternalRow, BinaryRow> partitionGetter =
                ManifestEntrySerializer.partitionGetter();
        Function<InternalRow, Integer> bucketGetter = ManifestEntrySerializer.bucketGetter();
        Function<InternalRow, Integer> totalBucketGetter =
                ManifestEntrySerializer.totalBucketGetter();
        return row -> {
            if (numOfBuckets != totalBucketGetter.apply(row)) {
                return true;
            }

            return manifestCacheFilter.test(partitionGetter.apply(row), bucketGetter.apply(row));
        };
    }

    // ------------------------------------------------------------------------
    // End Thread Safe Methods
    // ------------------------------------------------------------------------
}
