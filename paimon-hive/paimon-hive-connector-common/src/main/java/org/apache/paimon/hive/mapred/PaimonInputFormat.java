/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.paimon.hive.mapred;

import org.apache.paimon.hive.RowDataContainer;
import org.apache.paimon.predicate.Predicate;
import org.apache.paimon.predicate.PredicateBuilder;
import org.apache.paimon.table.FileStoreTable;
import org.apache.paimon.table.source.DataSplit;
import org.apache.paimon.table.source.InnerTableScan;
import org.apache.paimon.table.source.ReadBuilder;
import org.apache.paimon.types.RowType;

import org.apache.hadoop.hive.metastore.api.hive_metastoreConstants;
import org.apache.hadoop.hive.ql.io.IOConstants;
import org.apache.hadoop.hive.serde2.ColumnProjectionUtils;
import org.apache.hadoop.hive.serde2.SerDeUtils;
import org.apache.hadoop.mapred.InputFormat;
import org.apache.hadoop.mapred.InputSplit;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.RecordReader;
import org.apache.hadoop.mapred.Reporter;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.apache.paimon.hive.utils.HiveUtils.createFileStoreTable;
import static org.apache.paimon.hive.utils.HiveUtils.createPredicate;

/**
 * {@link InputFormat} for paimon. It divides all files into {@link InputSplit}s (one split per
 * bucket) and creates {@link RecordReader} for each split.
 */
public class PaimonInputFormat implements InputFormat<Void, RowDataContainer> {

    @Override
    public InputSplit[] getSplits(JobConf jobConf, int numSplits) {
        FileStoreTable table = createFileStoreTable(jobConf);
        List<Predicate> predicates = new ArrayList<>();
        createPredicate(table.schema(), jobConf, false).ifPresent(predicates::add);

        // If the path of the Paimon table is moved from the location of the Hive table to
        // properties (see HiveCatalogOptions#LOCATION_IN_PROPERTIES), Hive will add a location for
        // this table based on the warehouse, database, and table automatically. When querying by
        // Hive, an exception may occur because the specified path for split for Paimon may not
        // match the location of Hive. To work around this problem, we specify the path for split as
        // the location of Hive.
        String locations = jobConf.get(FileInputFormat.INPUT_DIR);

        List<PaimonInputSplit> splits = new ArrayList<>();
        // locations may contain multiple partitions
        for (String location : locations.split(",")) {
            List<Predicate> predicatePerPartition = new ArrayList<>(predicates);
            createPartitionPredicate(
                            table.schema().logicalRowType(),
                            table.schema().partitionKeys(),
                            location)
                    .ifPresent(predicatePerPartition::add);

            InnerTableScan scan = table.newScan();
            if (predicatePerPartition.size() > 0) {
                scan.withFilter(PredicateBuilder.and(predicatePerPartition));
            }
            scan.plan()
                    .splits()
                    .forEach(
                            split -> splits.add(new PaimonInputSplit(location, (DataSplit) split)));
        }
        return splits.toArray(new InputSplit[0]);
    }

    private Optional<Predicate> createPartitionPredicate(
            RowType rowType, List<String> partitionKeys, String partitionDir) {
        Set<String> partitionKeySet = new HashSet<>(partitionKeys);
        LinkedHashMap<String, String> partition = new LinkedHashMap<>();
        for (String s : partitionDir.split("/")) {
            s = s.trim();
            if (s.isEmpty()) {
                continue;
            }
            String[] kv = s.split("=");
            if (kv.length != 2) {
                continue;
            }
            if (partitionKeySet.contains(kv[0])) {
                partition.put(kv[0], kv[1]);
            }
        }
        if (partition.isEmpty() || partition.size() != partitionKeys.size()) {
            return Optional.empty();
        } else {
            return Optional.ofNullable(PredicateBuilder.partition(partition, rowType));
        }
    }

    @Override
    public RecordReader<Void, RowDataContainer> getRecordReader(
            InputSplit inputSplit, JobConf jobConf, Reporter reporter) throws IOException {
        FileStoreTable table = createFileStoreTable(jobConf);
        PaimonInputSplit split = (PaimonInputSplit) inputSplit;
        ReadBuilder readBuilder = table.newReadBuilder();
        createPredicate(table.schema(), jobConf, true).ifPresent(readBuilder::withFilter);
        List<String> paimonColumns = table.schema().fieldNames();
        return new PaimonRecordReader(
                readBuilder,
                split,
                paimonColumns,
                getHiveColumns(jobConf).orElse(paimonColumns),
                Arrays.asList(getSelectedColumns(jobConf)));
    }

    private Optional<List<String>> getHiveColumns(JobConf jobConf) {
        String columns = jobConf.get(IOConstants.SCHEMA_EVOLUTION_COLUMNS);
        if (columns == null) {
            columns = jobConf.get(hive_metastoreConstants.META_TABLE_COLUMNS);
        }
        String delimiter =
                jobConf.get(
                        // serdeConstants.COLUMN_NAME_DELIMITER is not defined in earlier Hive
                        // versions, so we use a constant string instead
                        "column.name.delimite", String.valueOf(SerDeUtils.COMMA));
        if (columns == null || delimiter == null) {
            return Optional.empty();
        } else {
            return Optional.of(Arrays.asList(columns.split(delimiter)));
        }
    }

    private String[] getSelectedColumns(JobConf jobConf) {
        // when using tez engine or when same table is joined multiple times,
        // it is possible that some selected columns are duplicated
        return Arrays.stream(ColumnProjectionUtils.getReadColumnNames(jobConf))
                .distinct()
                .toArray(String[]::new);
    }
}
