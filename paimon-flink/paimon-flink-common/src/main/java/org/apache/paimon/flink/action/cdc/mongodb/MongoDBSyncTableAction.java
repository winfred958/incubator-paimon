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

package org.apache.paimon.flink.action.cdc.mongodb;

import org.apache.paimon.annotation.VisibleForTesting;
import org.apache.paimon.catalog.Identifier;
import org.apache.paimon.flink.FlinkConnectorOptions;
import org.apache.paimon.flink.action.ActionBase;
import org.apache.paimon.flink.sink.cdc.CdcSinkBuilder;
import org.apache.paimon.flink.sink.cdc.EventParser;
import org.apache.paimon.flink.sink.cdc.RichCdcMultiplexRecord;
import org.apache.paimon.flink.sink.cdc.RichCdcMultiplexRecordEventParser;
import org.apache.paimon.schema.Schema;
import org.apache.paimon.table.FileStoreTable;

import com.ververica.cdc.connectors.mongodb.source.MongoDBSource;
import com.ververica.cdc.connectors.mongodb.source.config.MongoDBSourceOptions;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;

import java.util.List;
import java.util.Map;

import static org.apache.paimon.utils.Preconditions.checkArgument;

/**
 * Represents an action to synchronize a specific MongoDB table with a target system.
 *
 * <p>This action is responsible for:
 *
 * <ul>
 *   <li>Validating the provided MongoDB configuration.
 *   <li>Checking and ensuring the existence of the target database and table.
 *   <li>Setting up the necessary Flink streaming environment for data synchronization.
 *   <li>Handling case sensitivity considerations for database and table names.
 * </ul>
 *
 * <p>Usage:
 *
 * <pre>
 * MongoDBSyncTableAction action = new MongoDBSyncTableAction(...);
 * action.run();
 * </pre>
 */
public class MongoDBSyncTableAction extends ActionBase {

    private final Configuration mongodbConfig;
    private final String database;
    private final String table;
    private final List<String> partitionKeys;
    private final Map<String, String> tableConfig;

    public MongoDBSyncTableAction(
            Map<String, String> mongodbConfig,
            String warehouse,
            String database,
            String table,
            List<String> partitionKeys,
            Map<String, String> catalogConfig,
            Map<String, String> tableConfig) {
        super(warehouse, catalogConfig);
        this.mongodbConfig = Configuration.fromMap(mongodbConfig);
        this.database = database;
        this.table = table;
        this.partitionKeys = partitionKeys;
        this.tableConfig = tableConfig;
    }

    @Override
    public void build(StreamExecutionEnvironment env) throws Exception {
        checkArgument(
                mongodbConfig.contains(MongoDBSourceOptions.COLLECTION),
                String.format(
                        "mongodb-conf [%s] must be specified.",
                        MongoDBSourceOptions.COLLECTION.key()));

        String tableList =
                mongodbConfig.get(MongoDBSourceOptions.DATABASE)
                        + "\\."
                        + mongodbConfig.get(MongoDBSourceOptions.COLLECTION);
        MongoDBSource<String> source =
                MongoDBActionUtils.buildMongodbSource(mongodbConfig, tableList);

        boolean caseSensitive = catalog.caseSensitive();

        if (!caseSensitive) {
            validateCaseInsensitive();
        }

        MongodbSchema mongodbSchema = MongodbSchema.getMongodbSchema(mongodbConfig);
        catalog.createDatabase(database, true);

        Identifier identifier = new Identifier(database, table);
        FileStoreTable table;

        // Check if table exists before trying to get or create it
        if (catalog.tableExists(identifier)) {
            table = (FileStoreTable) catalog.getTable(identifier);
        } else {
            Schema fromMongodb =
                    MongoDBActionUtils.buildPaimonSchema(
                            mongodbSchema, partitionKeys, tableConfig, caseSensitive);
            catalog.createTable(identifier, fromMongodb, false);
            table = (FileStoreTable) catalog.getTable(identifier);
        }

        EventParser.Factory<RichCdcMultiplexRecord> parserFactory =
                RichCdcMultiplexRecordEventParser::new;

        CdcSinkBuilder<RichCdcMultiplexRecord> sinkBuilder =
                new CdcSinkBuilder<RichCdcMultiplexRecord>()
                        .withInput(
                                env.fromSource(
                                                source,
                                                WatermarkStrategy.noWatermarks(),
                                                "MongoDB Source")
                                        .flatMap(
                                                new MongoDBRecordParser(
                                                        caseSensitive, mongodbConfig)))
                        .withParserFactory(parserFactory)
                        .withTable(table)
                        .withIdentifier(identifier)
                        .withCatalogLoader(catalogLoader());
        String sinkParallelism = tableConfig.get(FlinkConnectorOptions.SINK_PARALLELISM.key());
        if (sinkParallelism != null) {
            sinkBuilder.withParallelism(Integer.parseInt(sinkParallelism));
        }
        sinkBuilder.build();
    }

    private void validateCaseInsensitive() {
        checkArgument(
                database.equals(database.toLowerCase()),
                String.format(
                        "Database name [%s] cannot contain upper case in case-insensitive catalog.",
                        database));
        checkArgument(
                table.equals(table.toLowerCase()),
                String.format(
                        "Collection prefix [%s] cannot contain upper case in case-insensitive catalog.",
                        table));
        for (String part : partitionKeys) {
            checkArgument(
                    part.equals(part.toLowerCase()),
                    String.format(
                            "Partition keys [%s] cannot contain upper case in case-insensitive catalog.",
                            partitionKeys));
        }
    }

    @VisibleForTesting
    public Map<String, String> tableConfig() {
        return tableConfig;
    }

    @VisibleForTesting
    public Map<String, String> catalogConfig() {
        return catalogConfig;
    }

    // ------------------------------------------------------------------------
    //  Flink run methods
    // ------------------------------------------------------------------------

    @Override
    public void run() throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        build(env);
        execute(env, String.format("MongoDB-Paimon Database Sync: %s", database));
    }
}
