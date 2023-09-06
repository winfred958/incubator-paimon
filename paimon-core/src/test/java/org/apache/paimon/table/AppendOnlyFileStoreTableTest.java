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

package org.apache.paimon.table;

import org.apache.paimon.CoreOptions;
import org.apache.paimon.WriteMode;
import org.apache.paimon.data.BinaryRow;
import org.apache.paimon.data.InternalRow;
import org.apache.paimon.data.serializer.InternalRowSerializer;
import org.apache.paimon.fs.FileIOFinder;
import org.apache.paimon.fs.local.LocalFileIO;
import org.apache.paimon.options.Options;
import org.apache.paimon.predicate.Predicate;
import org.apache.paimon.predicate.PredicateBuilder;
import org.apache.paimon.schema.Schema;
import org.apache.paimon.schema.SchemaManager;
import org.apache.paimon.schema.SchemaUtils;
import org.apache.paimon.schema.TableSchema;
import org.apache.paimon.table.sink.CommitMessage;
import org.apache.paimon.table.sink.StreamTableCommit;
import org.apache.paimon.table.sink.StreamTableWrite;
import org.apache.paimon.table.source.DataSplit;
import org.apache.paimon.table.source.ReadBuilder;
import org.apache.paimon.table.source.ScanMode;
import org.apache.paimon.table.source.Split;
import org.apache.paimon.table.source.StreamTableScan;
import org.apache.paimon.table.source.TableRead;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.apache.paimon.table.sink.KeyAndBucketExtractor.bucket;
import static org.apache.paimon.table.sink.KeyAndBucketExtractor.bucketKeyHashCode;
import static org.assertj.core.api.Assertions.assertThat;

/** Tests for {@link AppendOnlyFileStoreTable}. */
public class AppendOnlyFileStoreTableTest extends FileStoreTableTestBase {

    @Test
    public void testBatchReadWrite() throws Exception {
        writeData();
        FileStoreTable table = createFileStoreTable();

        List<Split> splits = toSplits(table.newSnapshotReader().read().dataSplits());
        TableRead read = table.newRead();
        assertThat(getResult(read, splits, binaryRow(1), 0, BATCH_ROW_TO_STRING))
                .hasSameElementsAs(
                        Arrays.asList(
                                "1|10|100|binary|varbinary|mapKey:mapVal|multiset",
                                "1|11|101|binary|varbinary|mapKey:mapVal|multiset",
                                "1|12|102|binary|varbinary|mapKey:mapVal|multiset",
                                "1|11|101|binary|varbinary|mapKey:mapVal|multiset",
                                "1|12|102|binary|varbinary|mapKey:mapVal|multiset"));
        assertThat(getResult(read, splits, binaryRow(2), 0, BATCH_ROW_TO_STRING))
                .hasSameElementsAs(
                        Arrays.asList(
                                "2|20|200|binary|varbinary|mapKey:mapVal|multiset",
                                "2|21|201|binary|varbinary|mapKey:mapVal|multiset",
                                "2|22|202|binary|varbinary|mapKey:mapVal|multiset",
                                "2|21|201|binary|varbinary|mapKey:mapVal|multiset"));
    }

    @Test
    public void testBatchProjection() throws Exception {
        writeData();
        FileStoreTable table = createFileStoreTable();

        List<Split> splits = toSplits(table.newSnapshotReader().read().dataSplits());
        TableRead read = table.newRead().withProjection(PROJECTION);
        assertThat(getResult(read, splits, binaryRow(1), 0, BATCH_PROJECTED_ROW_TO_STRING))
                .hasSameElementsAs(Arrays.asList("100|10", "101|11", "102|12", "101|11", "102|12"));
        assertThat(getResult(read, splits, binaryRow(2), 0, BATCH_PROJECTED_ROW_TO_STRING))
                .hasSameElementsAs(Arrays.asList("200|20", "201|21", "202|22", "201|21"));
    }

    @Test
    public void testBatchFilter() throws Exception {
        writeData();
        FileStoreTable table = createFileStoreTable();
        PredicateBuilder builder = new PredicateBuilder(table.schema().logicalRowType());

        Predicate predicate = builder.equal(2, 201L);
        List<Split> splits =
                toSplits(table.newSnapshotReader().withFilter(predicate).read().dataSplits());
        TableRead read = table.newRead();
        assertThat(getResult(read, splits, binaryRow(1), 0, BATCH_ROW_TO_STRING)).isEmpty();
        assertThat(getResult(read, splits, binaryRow(2), 0, BATCH_ROW_TO_STRING))
                .hasSameElementsAs(
                        Arrays.asList(
                                "2|21|201|binary|varbinary|mapKey:mapVal|multiset",
                                // this record is in the same file with the first "2|21|201"
                                "2|22|202|binary|varbinary|mapKey:mapVal|multiset",
                                "2|21|201|binary|varbinary|mapKey:mapVal|multiset"));
    }

    @Test
    public void testSplitOrder() throws Exception {
        FileStoreTable table = createFileStoreTable();

        StreamTableWrite write = table.newWrite(commitUser);
        StreamTableCommit commit = table.newCommit(commitUser);

        write.write(rowData(1, 10, 100L));
        commit.commit(0, write.prepareCommit(true, 0));

        write.write(rowData(2, 22, 202L));
        commit.commit(1, write.prepareCommit(true, 1));

        write.write(rowData(3, 33, 303L));
        commit.commit(2, write.prepareCommit(true, 2));
        write.close();

        List<Split> splits = toSplits(table.newSnapshotReader().read().dataSplits());
        int[] partitions =
                splits.stream()
                        .map(split -> ((DataSplit) split).partition().getInt(0))
                        .mapToInt(Integer::intValue)
                        .toArray();
        assertThat(partitions).containsExactly(1, 2, 3);
    }

    @Test
    public void testBatchSplitOrderByPartition() throws Exception {
        FileStoreTable table =
                createFileStoreTable(
                        options -> options.set(CoreOptions.SCAN_PLAN_SORT_PARTITION, true));

        StreamTableWrite write = table.newWrite(commitUser);
        StreamTableCommit commit = table.newCommit(commitUser);

        write.write(rowData(3, 33, 303L));
        commit.commit(0, write.prepareCommit(true, 0));

        write.write(rowData(1, 10, 100L));
        commit.commit(1, write.prepareCommit(true, 1));

        write.write(rowData(2, 22, 202L));
        commit.commit(2, write.prepareCommit(true, 2));

        write.close();

        List<Split> splits = toSplits(table.newSnapshotReader().read().dataSplits());
        int[] partitions =
                splits.stream()
                        .map(split -> ((DataSplit) split).partition().getInt(0))
                        .mapToInt(Integer::intValue)
                        .toArray();
        assertThat(partitions).containsExactly(1, 2, 3);
    }

    @Test
    public void testStreamingSplitInUnawareBucketMode() throws Exception {
        // in unaware-bucket mode, we split files into splits all the time
        FileStoreTable table =
                createUnawareBucketFileStoreTable(
                        options -> options.set(CoreOptions.SOURCE_SPLIT_TARGET_SIZE.key(), "1 M"));

        StreamTableScan scan = table.newStreamScan();

        StreamTableWrite write = table.newWrite(commitUser);
        StreamTableCommit commit = table.newCommit(commitUser);
        List<CommitMessage> result = new ArrayList<>();
        write.write(rowData(3, 33, 303L));
        result.addAll(write.prepareCommit(true, 0));
        write.write(rowData(1, 10, 100L));
        result.addAll(write.prepareCommit(true, 0));
        write.write(rowData(2, 22, 202L));
        result.addAll(write.prepareCommit(true, 0));
        commit.commit(0, result);
        result.clear();
        assertThat(scan.plan().splits().size()).isEqualTo(3);

        write.write(rowData(3, 33, 303L));
        result.addAll(write.prepareCommit(true, 1));
        write.write(rowData(1, 10, 100L));
        result.addAll(write.prepareCommit(true, 1));
        write.write(rowData(2, 22, 202L));
        result.addAll(write.prepareCommit(true, 1));
        commit.commit(1, result);
        assertThat(scan.plan().splits().size()).isEqualTo(3);

        write.close();
    }

    @Test
    public void testStreamingProjection() throws Exception {
        writeData();
        FileStoreTable table = createFileStoreTable();

        List<Split> splits =
                toSplits(table.newSnapshotReader().withMode(ScanMode.DELTA).read().dataSplits());
        TableRead read = table.newRead().withProjection(PROJECTION);
        assertThat(getResult(read, splits, binaryRow(1), 0, STREAMING_PROJECTED_ROW_TO_STRING))
                .isEqualTo(Arrays.asList("+101|11", "+102|12"));
        assertThat(getResult(read, splits, binaryRow(2), 0, STREAMING_PROJECTED_ROW_TO_STRING))
                .isEqualTo(Collections.singletonList("+201|21"));
    }

    @Test
    public void testStreamingFilter() throws Exception {
        writeData();
        FileStoreTable table = createFileStoreTable();
        PredicateBuilder builder = new PredicateBuilder(table.schema().logicalRowType());

        Predicate predicate = builder.equal(2, 101L);
        List<Split> splits =
                toSplits(
                        table.newSnapshotReader()
                                .withMode(ScanMode.DELTA)
                                .withFilter(predicate)
                                .read()
                                .dataSplits());
        TableRead read = table.newRead();
        assertThat(getResult(read, splits, binaryRow(1), 0, STREAMING_ROW_TO_STRING))
                .isEqualTo(
                        Arrays.asList(
                                "+1|11|101|binary|varbinary|mapKey:mapVal|multiset",
                                // this record is in the same file with "+1|11|101"
                                "+1|12|102|binary|varbinary|mapKey:mapVal|multiset"));
        assertThat(getResult(read, splits, binaryRow(2), 0, STREAMING_ROW_TO_STRING)).isEmpty();
    }

    @Test
    public void testSequentialRead() throws Exception {
        Random random = new Random();
        int numOfBucket = Math.max(random.nextInt(8), 1);
        FileStoreTable table = createFileStoreTable(numOfBucket);
        InternalRowSerializer serializer =
                new InternalRowSerializer(table.schema().logicalRowType());
        StreamTableWrite write = table.newWrite(commitUser);

        StreamTableCommit commit = table.newCommit(commitUser);
        List<Map<Integer, List<InternalRow>>> dataset = new ArrayList<>();
        Map<Integer, List<InternalRow>> dataPerBucket = new HashMap<>(numOfBucket);
        int numOfPartition = Math.max(random.nextInt(10), 1);
        for (int i = 0; i < numOfPartition; i++) {
            for (int j = 0; j < Math.max(random.nextInt(200), 1); j++) {
                BinaryRow data =
                        serializer
                                .toBinaryRow(rowData(i, random.nextInt(), random.nextLong()))
                                .copy();
                int bucket = bucket(bucketKeyHashCode(data), numOfBucket);
                dataPerBucket.compute(
                        bucket,
                        (k, v) -> {
                            if (v == null) {
                                v = new ArrayList<>();
                            }
                            v.add(data);
                            return v;
                        });
                write.write(data);
            }
            dataset.add(new HashMap<>(dataPerBucket));
            dataPerBucket.clear();
        }
        commit.commit(0, write.prepareCommit(true, 0));

        int partition = random.nextInt(numOfPartition);
        List<Integer> availableBucket = new ArrayList<>(dataset.get(partition).keySet());
        int bucket = availableBucket.get(random.nextInt(availableBucket.size()));

        Predicate partitionFilter =
                new PredicateBuilder(table.schema().logicalRowType()).equal(0, partition);
        List<Split> splits =
                toSplits(
                        table.newSnapshotReader()
                                .withFilter(partitionFilter)
                                .withBucket(bucket)
                                .read()
                                .dataSplits());
        TableRead read = table.newRead();

        assertThat(getResult(read, splits, binaryRow(partition), bucket, STREAMING_ROW_TO_STRING))
                .containsExactlyElementsOf(
                        dataset.get(partition).get(bucket).stream()
                                .map(STREAMING_ROW_TO_STRING)
                                .collect(Collectors.toList()));
    }

    @Test
    public void testBatchOrderWithCompaction() throws Exception {
        FileStoreTable table = createFileStoreTable();

        int number = 61;
        List<Integer> expected = new ArrayList<>();

        {
            StreamTableWrite write = table.newWrite(commitUser);
            StreamTableCommit commit = table.newCommit(commitUser);

            for (int i = 0; i < number; i++) {
                write.write(rowData(1, i, (long) i));
                commit.commit(i, write.prepareCommit(false, i));
                expected.add(i);
            }
            write.close();

            ReadBuilder readBuilder = table.newReadBuilder();
            List<Split> splits = readBuilder.newScan().plan().splits();
            List<Integer> result = new ArrayList<>();
            readBuilder
                    .newRead()
                    .createReader(splits)
                    .forEachRemaining(r -> result.add(r.getInt(1)));
            assertThat(result).containsExactlyElementsOf(expected);
        }

        // restore
        {
            StreamTableWrite write = table.newWrite(commitUser);
            StreamTableCommit commit = table.newCommit(commitUser);

            for (int i = number; i < number + 51; i++) {
                write.write(rowData(1, i, (long) i));
                commit.commit(i, write.prepareCommit(false, i));
                expected.add(i);
            }
            write.close();

            ReadBuilder readBuilder = table.newReadBuilder();
            List<Split> splits = readBuilder.newScan().plan().splits();
            List<Integer> result = new ArrayList<>();
            readBuilder
                    .newRead()
                    .createReader(splits)
                    .forEachRemaining(r -> result.add(r.getInt(1)));
            assertThat(result).containsExactlyElementsOf(expected);
        }
    }

    private void writeData() throws Exception {
        FileStoreTable table = createFileStoreTable();
        StreamTableWrite write = table.newWrite(commitUser);
        StreamTableCommit commit = table.newCommit(commitUser);

        write.write(rowData(1, 10, 100L));
        write.write(rowData(2, 20, 200L));
        write.write(rowData(1, 11, 101L));
        commit.commit(0, write.prepareCommit(true, 0));

        write.write(rowData(1, 12, 102L));
        write.write(rowData(2, 21, 201L));
        write.write(rowData(2, 22, 202L));
        commit.commit(1, write.prepareCommit(true, 1));

        write.write(rowData(1, 11, 101L));
        write.write(rowData(2, 21, 201L));
        write.write(rowData(1, 12, 102L));
        commit.commit(2, write.prepareCommit(true, 2));

        write.close();
    }

    @Override
    protected FileStoreTable createFileStoreTable(Consumer<Options> configure) throws Exception {
        Options conf = new Options();
        conf.set(CoreOptions.PATH, tablePath.toString());
        conf.set(CoreOptions.WRITE_MODE, WriteMode.APPEND_ONLY);
        configure.accept(conf);
        TableSchema tableSchema =
                SchemaUtils.forceCommit(
                        new SchemaManager(LocalFileIO.create(), tablePath),
                        new Schema(
                                ROW_TYPE.getFields(),
                                Collections.singletonList("pt"),
                                Collections.emptyList(),
                                conf.toMap(),
                                ""));
        return new AppendOnlyFileStoreTable(FileIOFinder.find(tablePath), tablePath, tableSchema);
    }

    @Override
    protected FileStoreTable overwriteTestFileStoreTable() throws Exception {
        Options conf = new Options();
        conf.set(CoreOptions.PATH, tablePath.toString());
        conf.set(CoreOptions.WRITE_MODE, WriteMode.APPEND_ONLY);
        TableSchema tableSchema =
                SchemaUtils.forceCommit(
                        new SchemaManager(LocalFileIO.create(), tablePath),
                        new Schema(
                                OVERWRITE_TEST_ROW_TYPE.getFields(),
                                Arrays.asList("pt0", "pt1"),
                                Collections.emptyList(),
                                conf.toMap(),
                                ""));
        return new AppendOnlyFileStoreTable(FileIOFinder.find(tablePath), tablePath, tableSchema);
    }

    protected FileStoreTable createUnawareBucketFileStoreTable(Consumer<Options> configure)
            throws Exception {
        Options conf = new Options();
        conf.set(CoreOptions.PATH, tablePath.toString());
        conf.set(CoreOptions.WRITE_MODE, WriteMode.APPEND_ONLY);
        conf.set(CoreOptions.BUCKET, -1);
        configure.accept(conf);
        TableSchema tableSchema =
                SchemaUtils.forceCommit(
                        new SchemaManager(LocalFileIO.create(), tablePath),
                        new Schema(
                                ROW_TYPE.getFields(),
                                Collections.emptyList(),
                                Collections.emptyList(),
                                conf.toMap(),
                                ""));
        return new AppendOnlyFileStoreTable(FileIOFinder.find(tablePath), tablePath, tableSchema);
    }
}
