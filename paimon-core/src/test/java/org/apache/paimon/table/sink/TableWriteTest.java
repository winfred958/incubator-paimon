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

package org.apache.paimon.table.sink;

import org.apache.paimon.CoreOptions;
import org.apache.paimon.data.GenericRow;
import org.apache.paimon.data.InternalRow;
import org.apache.paimon.fs.Path;
import org.apache.paimon.fs.local.LocalFileIO;
import org.apache.paimon.operation.AbstractFileStoreWrite;
import org.apache.paimon.options.MemorySize;
import org.apache.paimon.options.Options;
import org.apache.paimon.reader.RecordReaderIterator;
import org.apache.paimon.schema.Schema;
import org.apache.paimon.schema.SchemaManager;
import org.apache.paimon.schema.SchemaUtils;
import org.apache.paimon.schema.TableSchema;
import org.apache.paimon.table.FileStoreTable;
import org.apache.paimon.table.FileStoreTableFactory;
import org.apache.paimon.table.source.TableScan;
import org.apache.paimon.types.DataType;
import org.apache.paimon.types.DataTypes;
import org.apache.paimon.types.RowType;
import org.apache.paimon.utils.TraceableFileIO;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/** Tests for {@link TableWriteImpl}. */
public class TableWriteTest {

    private static final RowType ROW_TYPE =
            RowType.of(
                    new DataType[] {DataTypes.INT(), DataTypes.INT(), DataTypes.BIGINT()},
                    new String[] {"pt", "k", "v"});

    @TempDir java.nio.file.Path tempDir;

    private Path tablePath;
    private String commitUser;

    @BeforeEach
    public void before() {
        tablePath = new Path(TraceableFileIO.SCHEME + "://" + tempDir.toString());
        commitUser = UUID.randomUUID().toString();
    }

    @AfterEach
    public void after() {
        // assert all connections are closed
        Predicate<Path> pathPredicate = path -> path.toString().contains(tempDir.toString());
        assertThat(TraceableFileIO.openInputStreams(pathPredicate)).isEmpty();
        assertThat(TraceableFileIO.openOutputStreams(pathPredicate)).isEmpty();
    }

    @Test
    public void testExtractAndRecoverState() throws Exception {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int commitCount = random.nextInt(10) + 1;
        int extractCount = random.nextInt(10) + 1;
        int numRecords = 1000;
        int numPartitions = 2;
        int numKeys = 100;

        Map<Integer, List<Event>> events = new HashMap<>();
        for (int i = 0; i < commitCount; i++) {
            int prepareTime = random.nextInt(numRecords);
            int commitTime = random.nextInt(prepareTime, numRecords);
            events.computeIfAbsent(prepareTime, k -> new ArrayList<>()).add(Event.PREPARE_COMMIT);
            events.computeIfAbsent(commitTime, k -> new ArrayList<>()).add(Event.COMMIT);
        }
        for (int i = 0; i < extractCount; i++) {
            int extractTime = random.nextInt(numRecords);
            List<Event> eventList = events.computeIfAbsent(extractTime, k -> new ArrayList<>());
            eventList.add(random.nextInt(eventList.size() + 1), Event.EXTRACT_STATE);
        }

        FileStoreTable table = createFileStoreTable();
        TableWriteImpl<?> write = table.newWrite(commitUser);
        StreamTableCommit commit = table.newCommit(commitUser);

        Map<String, Long> expected = new HashMap<>();
        List<List<CommitMessage>> commitList = new ArrayList<>();
        int commitId = 0;
        for (int i = 0; i < numRecords; i++) {
            if (events.containsKey(i)) {
                List<Event> eventList = events.get(i);
                for (Event event : eventList) {
                    switch (event) {
                        case PREPARE_COMMIT:
                            List<CommitMessage> messages =
                                    write.prepareCommit(false, commitList.size());
                            commitList.add(messages);
                            break;
                        case COMMIT:
                            commit.commit(commitId, commitList.get(commitId));
                            commitId++;
                            break;
                        case EXTRACT_STATE:
                            List<? extends AbstractFileStoreWrite.State<?>> state =
                                    write.checkpoint();
                            write.close();
                            write = table.newWrite(commitUser);
                            write.restore((List) state);
                            break;
                    }
                }
            }

            int partition = random.nextInt(numPartitions);
            int key = random.nextInt(numKeys);
            long value = random.nextLong();
            write.write(GenericRow.of(partition, key, value));
            expected.put(partition + "|" + key, value);
        }

        assertThat(commitId).isEqualTo(commitCount);
        List<CommitMessage> messages = write.prepareCommit(false, commitCount);
        commit.commit(commitCount, messages);
        write.close();
        commit.close();

        Map<String, Long> actual = new HashMap<>();
        TableScan.Plan plan = table.newScan().plan();
        try (RecordReaderIterator<InternalRow> it =
                new RecordReaderIterator<>(table.newRead().createReader(plan))) {
            while (it.hasNext()) {
                InternalRow row = it.next();
                actual.put(row.getInt(0) + "|" + row.getInt(1), row.getLong(2));
            }
        }
        assertThat(actual).isEqualTo(expected);
    }

    private enum Event {
        PREPARE_COMMIT,
        COMMIT,
        EXTRACT_STATE
    }

    private FileStoreTable createFileStoreTable() throws Exception {
        Options conf = new Options();
        conf.set(CoreOptions.BUCKET, 2);
        conf.set(CoreOptions.WRITE_BUFFER_SIZE, new MemorySize(4096 * 3));
        conf.set(CoreOptions.PAGE_SIZE, new MemorySize(4096));

        TableSchema tableSchema =
                SchemaUtils.forceCommit(
                        new SchemaManager(LocalFileIO.create(), tablePath),
                        new Schema(
                                ROW_TYPE.getFields(),
                                Collections.singletonList("pt"),
                                Arrays.asList("pt", "k"),
                                conf.toMap(),
                                ""));
        return FileStoreTableFactory.create(LocalFileIO.create(), tablePath, tableSchema);
    }
}
