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

package org.apache.paimon.flink.action.cdc.kafka;

import org.apache.paimon.table.FileStoreTable;
import org.apache.paimon.types.DataType;
import org.apache.paimon.types.DataTypes;
import org.apache.paimon.types.RowType;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.apache.paimon.testutils.assertj.AssertionUtils.anyCauseMatches;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** IT cases for {@link KafkaOggSyncTableActionITCase}. */
public class KafkaOggSyncTableActionITCase extends KafkaActionITCaseBase {

    @Test
    @Timeout(60)
    public void testSchemaEvolution() throws Exception {
        runSingleTableSchemaEvolution("schemaevolution");
    }

    private void runSingleTableSchemaEvolution(String sourceDir) throws Exception {
        final String topic = "schema_evolution";
        createTestTopic(topic, 1, 1);
        // ---------- Write the ogg json into Kafka -------------------
        List<String> lines =
                readLines(String.format("kafka/ogg/table/%s/ogg-data-1.txt", sourceDir));
        try {
            writeRecordsToKafka(topic, lines);
        } catch (Exception e) {
            throw new Exception("Failed to write ogg data to Kafka.", e);
        }
        Map<String, String> kafkaConfig = getBasicKafkaConfig();
        kafkaConfig.put("value.format", "ogg-json");
        kafkaConfig.put("topic", topic);
        KafkaSyncTableAction action =
                syncTableActionBuilder(kafkaConfig)
                        .withPrimaryKeys("id")
                        .withTableConfig(getBasicTableConfig())
                        .build();
        runActionWithDefaultEnv(action);

        testSchemaEvolutionImpl(topic, sourceDir);
    }

    private void testSchemaEvolutionImpl(String topic, String sourceDir) throws Exception {
        FileStoreTable table = getFileStoreTable(tableName);

        RowType rowType =
                RowType.of(
                        new DataType[] {
                            DataTypes.STRING().notNull(),
                            DataTypes.STRING(),
                            DataTypes.STRING(),
                            DataTypes.STRING()
                        },
                        new String[] {"id", "name", "description", "weight"});
        List<String> primaryKeys = Collections.singletonList("id");
        List<String> expected =
                Arrays.asList(
                        "+I[101, scooter, Small 2-wheel scooter, 3.140000104904175]",
                        "+I[102, car battery, 12V car battery, 8.100000381469727]");
        waitForResult(expected, table, rowType, primaryKeys);

        try {
            writeRecordsToKafka(
                    topic,
                    readLines(String.format("kafka/ogg/table/%s/ogg-data-2.txt", sourceDir)));
        } catch (Exception e) {
            throw new Exception("Failed to write ogg data to Kafka.", e);
        }
        rowType =
                RowType.of(
                        new DataType[] {
                            DataTypes.STRING().notNull(),
                            DataTypes.STRING(),
                            DataTypes.STRING(),
                            DataTypes.STRING(),
                            DataTypes.STRING()
                        },
                        new String[] {"id", "name", "description", "weight", "age"});
        expected =
                Arrays.asList(
                        "+I[101, scooter, Small 2-wheel scooter, 3.140000104904175, NULL]",
                        "+I[102, car battery, 12V car battery, 8.100000381469727, NULL]",
                        "+I[103, 12-pack drill bits, 12-pack of drill bits with sizes ranging from #40 to #3, 0.800000011920929, 18]",
                        "+I[104, hammer, 12oz carpenter's hammer, 0.75, 24]");
        waitForResult(expected, table, rowType, primaryKeys);

        try {
            writeRecordsToKafka(
                    topic,
                    readLines(String.format("kafka/ogg/table/%s/ogg-data-3.txt", sourceDir)));
        } catch (Exception e) {
            throw new Exception("Failed to write ogg data to Kafka.", e);
        }
        rowType =
                RowType.of(
                        new DataType[] {
                            DataTypes.STRING().notNull(),
                            DataTypes.STRING(),
                            DataTypes.STRING(),
                            DataTypes.STRING(),
                            DataTypes.STRING(),
                            DataTypes.STRING()
                        },
                        new String[] {"id", "name", "description", "weight", "age", "address"});
        expected =
                Arrays.asList(
                        "+I[102, car battery, 12V car battery, 8.100000381469727, NULL, NULL]",
                        "+I[103, 12-pack drill bits, 12-pack of drill bits with sizes ranging from #40 to #3, 0.800000011920929, 18, NULL]",
                        "+I[104, hammer, 12oz carpenter's hammer, 0.75, 24, NULL]",
                        "+I[105, hammer, 14oz carpenter's hammer, 0.875, NULL, Beijing]",
                        "+I[107, rocks, box of assorted rocks, 5.300000190734863, NULL, NULL]");
        waitForResult(expected, table, rowType, primaryKeys);
    }

    @Test
    @Timeout(60)
    public void testNotSupportFormat() throws Exception {
        final String topic = "not_support";
        createTestTopic(topic, 1, 1);
        // ---------- Write the ogg json into Kafka -------------------
        List<String> lines = readLines("kafka/ogg/table/schemaevolution/ogg-data-1.txt");
        try {
            writeRecordsToKafka(topic, lines);
        } catch (Exception e) {
            throw new Exception("Failed to write ogg data to Kafka.", e);
        }
        Map<String, String> kafkaConfig = getBasicKafkaConfig();
        kafkaConfig.put("value.format", "togg-json");
        kafkaConfig.put("topic", topic);
        KafkaSyncTableAction action =
                syncTableActionBuilder(kafkaConfig)
                        .withPrimaryKeys("id")
                        .withTableConfig(getBasicTableConfig())
                        .build();

        assertThatThrownBy(action::run)
                .satisfies(
                        anyCauseMatches(
                                UnsupportedOperationException.class,
                                "This format: togg-json is not supported."));
    }

    @Test
    @Timeout(60)
    public void testAssertSchemaCompatible() throws Exception {
        final String topic = "assert_schema_compatible";
        createTestTopic(topic, 1, 1);
        // ---------- Write the ogg json into Kafka -------------------
        List<String> lines = readLines("kafka/ogg/table/schemaevolution/ogg-data-1.txt");
        try {
            writeRecordsToKafka(topic, lines);
        } catch (Exception e) {
            throw new Exception("Failed to write ogg data to Kafka.", e);
        }
        Map<String, String> kafkaConfig = getBasicKafkaConfig();
        kafkaConfig.put("value.format", "ogg-json");
        kafkaConfig.put("topic", topic);

        // create an incompatible table
        createFileStoreTable(
                RowType.of(
                        new DataType[] {DataTypes.STRING(), DataTypes.STRING()},
                        new String[] {"k", "v1"}),
                Collections.emptyList(),
                Collections.singletonList("k"),
                Collections.emptyMap());

        KafkaSyncTableAction action =
                syncTableActionBuilder(kafkaConfig)
                        .withPrimaryKeys("id")
                        .withTableConfig(getBasicTableConfig())
                        .build();

        assertThatThrownBy(action::run)
                .satisfies(
                        anyCauseMatches(
                                IllegalArgumentException.class,
                                "Paimon schema and source table schema are not compatible.\n"
                                        + "Paimon fields are: [`k` STRING NOT NULL, `v1` STRING].\n"
                                        + "Source table fields are: [`id` STRING NOT NULL, `name` STRING, `description` STRING, `weight` STRING]"));
    }

    @Test
    @Timeout(60)
    public void testStarUpOptionSpecific() throws Exception {
        final String topic = "start_up_specific";
        createTestTopic(topic, 1, 1);
        // ---------- Write the ogg json into Kafka -------------------
        List<String> lines = readLines("kafka/ogg/table/startupmode/ogg-data-1.txt");
        try {
            writeRecordsToKafka(topic, lines);
        } catch (Exception e) {
            throw new Exception("Failed to write ogg data to Kafka.", e);
        }
        Map<String, String> kafkaConfig = getBasicKafkaConfig();
        kafkaConfig.put("value.format", "ogg-json");
        kafkaConfig.put("topic", topic);
        kafkaConfig.put("scan.startup.mode", "specific-offsets");
        kafkaConfig.put("scan.startup.specific-offsets", "partition:0,offset:1");
        KafkaSyncTableAction action =
                syncTableActionBuilder(kafkaConfig)
                        .withPrimaryKeys("id")
                        .withTableConfig(getBasicTableConfig())
                        .build();
        runActionWithDefaultEnv(action);

        FileStoreTable table = getFileStoreTable(tableName);

        RowType rowType =
                RowType.of(
                        new DataType[] {
                            DataTypes.STRING().notNull(),
                            DataTypes.STRING(),
                            DataTypes.STRING(),
                            DataTypes.STRING()
                        },
                        new String[] {"id", "name", "description", "weight"});
        List<String> primaryKeys = Collections.singletonList("id");
        // topic has two records we read two
        List<String> expected =
                Collections.singletonList(
                        "+I[102, car battery, 12V car battery, 8.100000381469727]");
        waitForResult(expected, table, rowType, primaryKeys);
    }

    @Test
    @Timeout(60)
    public void testStarUpOptionLatest() throws Exception {
        final String topic = "start_up_latest";
        createTestTopic(topic, 1, 1);
        // ---------- Write the ogg json into Kafka -------------------
        List<String> lines = readLines("kafka/ogg/table/startupmode/ogg-data-1.txt");
        try {
            writeRecordsToKafka(topic, lines);
        } catch (Exception e) {
            throw new Exception("Failed to write ogg data to Kafka.", e);
        }
        Map<String, String> kafkaConfig = getBasicKafkaConfig();
        kafkaConfig.put("value.format", "ogg-json");
        kafkaConfig.put("topic", topic);
        kafkaConfig.put("scan.startup.mode", "latest-offset");
        KafkaSyncTableAction action =
                syncTableActionBuilder(kafkaConfig)
                        .withPrimaryKeys("id")
                        .withTableConfig(getBasicTableConfig())
                        .build();
        runActionWithDefaultEnv(action);

        Thread.sleep(5000);
        FileStoreTable table = getFileStoreTable(tableName);
        try {
            writeRecordsToKafka(topic, readLines("kafka/ogg/table/startupmode/ogg-data-2.txt"));
        } catch (Exception e) {
            throw new Exception("Failed to write ogg data to Kafka.", e);
        }

        RowType rowType =
                RowType.of(
                        new DataType[] {
                            DataTypes.STRING().notNull(),
                            DataTypes.STRING(),
                            DataTypes.STRING(),
                            DataTypes.STRING()
                        },
                        new String[] {"id", "name", "description", "weight"});
        List<String> primaryKeys = Collections.singletonList("id");
        // topic has four records we read two
        List<String> expected =
                Arrays.asList(
                        "+I[103, 12-pack drill bits, 12-pack of drill bits with sizes ranging from #40 to #3, 0.800000011920929]",
                        "+I[104, hammer, 12oz carpenter's hammer, 0.75]");
        waitForResult(expected, table, rowType, primaryKeys);
    }

    @Test
    @Timeout(60)
    public void testStarUpOptionTimestamp() throws Exception {
        final String topic = "start_up_timestamp";
        createTestTopic(topic, 1, 1);
        // ---------- Write the ogg json into Kafka -------------------
        List<String> lines = readLines("kafka/ogg/table/startupmode/ogg-data-1.txt");
        try {
            writeRecordsToKafka(topic, lines);
        } catch (Exception e) {
            throw new Exception("Failed to write ogg data to Kafka.", e);
        }
        Map<String, String> kafkaConfig = getBasicKafkaConfig();
        kafkaConfig.put("value.format", "ogg-json");
        kafkaConfig.put("topic", topic);
        kafkaConfig.put("scan.startup.mode", "timestamp");
        kafkaConfig.put(
                "scan.startup.timestamp-millis", String.valueOf(System.currentTimeMillis()));
        KafkaSyncTableAction action =
                syncTableActionBuilder(kafkaConfig)
                        .withPrimaryKeys("id")
                        .withTableConfig(getBasicTableConfig())
                        .build();
        runActionWithDefaultEnv(action);

        try {
            writeRecordsToKafka(topic, readLines("kafka/ogg/table/startupmode/ogg-data-2.txt"));
        } catch (Exception e) {
            throw new Exception("Failed to write ogg data to Kafka.", e);
        }
        FileStoreTable table = getFileStoreTable(tableName);

        RowType rowType =
                RowType.of(
                        new DataType[] {
                            DataTypes.STRING().notNull(),
                            DataTypes.STRING(),
                            DataTypes.STRING(),
                            DataTypes.STRING()
                        },
                        new String[] {"id", "name", "description", "weight"});
        List<String> primaryKeys = Collections.singletonList("id");
        // topic has four records we read two
        List<String> expected =
                Arrays.asList(
                        "+I[103, 12-pack drill bits, 12-pack of drill bits with sizes ranging from #40 to #3, 0.800000011920929]",
                        "+I[104, hammer, 12oz carpenter's hammer, 0.75]");
        waitForResult(expected, table, rowType, primaryKeys);
    }

    @Test
    @Timeout(60)
    public void testStarUpOptionEarliest() throws Exception {
        final String topic = "start_up_earliest";
        createTestTopic(topic, 1, 1);
        // ---------- Write the ogg json into Kafka -------------------
        List<String> lines = readLines("kafka/ogg/table/startupmode/ogg-data-1.txt");
        try {
            writeRecordsToKafka(topic, lines);
        } catch (Exception e) {
            throw new Exception("Failed to write ogg data to Kafka.", e);
        }
        Map<String, String> kafkaConfig = getBasicKafkaConfig();
        kafkaConfig.put("value.format", "ogg-json");
        kafkaConfig.put("topic", topic);
        kafkaConfig.put("scan.startup.mode", "earliest-offset");
        KafkaSyncTableAction action =
                syncTableActionBuilder(kafkaConfig)
                        .withPrimaryKeys("id")
                        .withTableConfig(getBasicTableConfig())
                        .build();
        runActionWithDefaultEnv(action);

        try {
            writeRecordsToKafka(topic, readLines("kafka/ogg/table/startupmode/ogg-data-2.txt"));
        } catch (Exception e) {
            throw new Exception("Failed to write ogg data to Kafka.", e);
        }
        FileStoreTable table = getFileStoreTable(tableName);

        RowType rowType =
                RowType.of(
                        new DataType[] {
                            DataTypes.STRING().notNull(),
                            DataTypes.STRING(),
                            DataTypes.STRING(),
                            DataTypes.STRING()
                        },
                        new String[] {"id", "name", "description", "weight"});
        List<String> primaryKeys = Collections.singletonList("id");
        // topic has four records we read all
        List<String> expected =
                Arrays.asList(
                        "+I[101, scooter, Small 2-wheel scooter, 3.140000104904175]",
                        "+I[102, car battery, 12V car battery, 8.100000381469727]",
                        "+I[103, 12-pack drill bits, 12-pack of drill bits with sizes ranging from #40 to #3, 0.800000011920929]",
                        "+I[104, hammer, 12oz carpenter's hammer, 0.75]");
        waitForResult(expected, table, rowType, primaryKeys);
    }

    @Test
    @Timeout(60)
    public void testStarUpOptionGroup() throws Exception {
        final String topic = "start_up_group";
        createTestTopic(topic, 1, 1);
        // ---------- Write the ogg json into Kafka -------------------
        List<String> lines = readLines("kafka/ogg/table/startupmode/ogg-data-1.txt");
        try {
            writeRecordsToKafka(topic, lines);
        } catch (Exception e) {
            throw new Exception("Failed to write ogg data to Kafka.", e);
        }
        Map<String, String> kafkaConfig = getBasicKafkaConfig();
        kafkaConfig.put("value.format", "ogg-json");
        kafkaConfig.put("topic", topic);
        kafkaConfig.put("scan.startup.mode", "group-offsets");
        KafkaSyncTableAction action =
                syncTableActionBuilder(kafkaConfig)
                        .withPrimaryKeys("id")
                        .withTableConfig(getBasicTableConfig())
                        .build();
        runActionWithDefaultEnv(action);

        try {
            writeRecordsToKafka(topic, readLines("kafka/ogg/table/startupmode/ogg-data-2.txt"));
        } catch (Exception e) {
            throw new Exception("Failed to write ogg data to Kafka.", e);
        }
        FileStoreTable table = getFileStoreTable(tableName);

        RowType rowType =
                RowType.of(
                        new DataType[] {
                            DataTypes.STRING().notNull(),
                            DataTypes.STRING(),
                            DataTypes.STRING(),
                            DataTypes.STRING()
                        },
                        new String[] {"id", "name", "description", "weight"});
        List<String> primaryKeys = Collections.singletonList("id");
        // topic has four records we read all
        List<String> expected =
                Arrays.asList(
                        "+I[101, scooter, Small 2-wheel scooter, 3.140000104904175]",
                                "+I[102, car battery, 12V car battery, 8.100000381469727]",
                        "+I[103, 12-pack drill bits, 12-pack of drill bits with sizes ranging from #40 to #3, 0.800000011920929]",
                                "+I[104, hammer, 12oz carpenter's hammer, 0.75]");
        waitForResult(expected, table, rowType, primaryKeys);
    }

    @Test
    @Timeout(60)
    public void testComputedColumn() throws Exception {
        String topic = "computed_column";
        createTestTopic(topic, 1, 1);

        List<String> lines = readLines("kafka/ogg/table/computedcolumn/ogg-data-1.txt");
        try {
            writeRecordsToKafka(topic, lines);
        } catch (Exception e) {
            throw new Exception("Failed to write canal data to Kafka.", e);
        }
        Map<String, String> kafkaConfig = getBasicKafkaConfig();
        kafkaConfig.put("value.format", "ogg-json");
        kafkaConfig.put("topic", topic);
        KafkaSyncTableAction action =
                syncTableActionBuilder(kafkaConfig)
                        .withPartitionKeys("_year")
                        .withPrimaryKeys("_id", "_year")
                        .withComputedColumnArgs("_year=year(_date)")
                        .withTableConfig(getBasicTableConfig())
                        .build();
        runActionWithDefaultEnv(action);

        RowType rowType =
                RowType.of(
                        new DataType[] {
                            DataTypes.STRING().notNull(),
                            DataTypes.STRING(),
                            DataTypes.INT().notNull()
                        },
                        new String[] {"_id", "_date", "_year"});
        waitForResult(
                Collections.singletonList("+I[101, 2023-03-23, 2023]"),
                getFileStoreTable(tableName),
                rowType,
                Arrays.asList("_id", "_year"));
    }
}
