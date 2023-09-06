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

package org.apache.paimon.flink.action.cdc;

import org.apache.paimon.catalog.Identifier;
import org.apache.paimon.flink.action.MultiTablesSinkMode;
import org.apache.paimon.flink.sink.cdc.UpdatedDataFieldsProcessFunction;
import org.apache.paimon.schema.Schema;
import org.apache.paimon.schema.TableSchema;
import org.apache.paimon.types.DataField;
import org.apache.paimon.types.DataType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.apache.paimon.flink.action.MultiTablesSinkMode.COMBINED;
import static org.apache.paimon.flink.action.MultiTablesSinkMode.DIVIDED;
import static org.apache.paimon.utils.Preconditions.checkArgument;

/** Common utils for CDC Action. */
public class CdcActionCommonUtils {

    private static final Logger LOG = LoggerFactory.getLogger(CdcActionCommonUtils.class);

    public static void assertSchemaCompatible(
            TableSchema paimonSchema, List<DataField> sourceTableFields) {
        if (!schemaCompatible(paimonSchema, sourceTableFields)) {
            throw new IllegalArgumentException(
                    "Paimon schema and source table schema are not compatible.\n"
                            + "Paimon fields are: "
                            + paimonSchema.fields()
                            + ".\nSource table fields are: "
                            + sourceTableFields);
        }
    }

    public static boolean schemaCompatible(
            TableSchema paimonSchema, List<DataField> sourceTableFields) {
        for (DataField field : sourceTableFields) {
            int idx = paimonSchema.fieldNames().indexOf(field.name());
            if (idx < 0) {
                LOG.info("Cannot find field '{}' in Paimon table.", field.name());
                return false;
            }
            DataType type = paimonSchema.fields().get(idx).type();
            if (UpdatedDataFieldsProcessFunction.canConvert(field.type(), type)
                    != UpdatedDataFieldsProcessFunction.ConvertAction.CONVERT) {
                LOG.info(
                        "Cannot convert field '{}' from source table type '{}' to Paimon type '{}'.",
                        field.name(),
                        field.type(),
                        type);
                return false;
            }
        }
        return true;
    }

    public static <T> LinkedHashMap<String, T> mapKeyCaseConvert(
            LinkedHashMap<String, T> origin,
            boolean caseSensitive,
            Function<String, String> duplicateErrMsg) {
        return mapKeyCaseConvert(origin, caseSensitive, duplicateErrMsg, LinkedHashMap::new);
    }

    public static <T> Map<String, T> mapKeyCaseConvert(
            Map<String, T> origin,
            boolean caseSensitive,
            Function<String, String> duplicateErrMsg) {
        return mapKeyCaseConvert(origin, caseSensitive, duplicateErrMsg, HashMap::new);
    }

    private static <T, M extends Map<String, T>> M mapKeyCaseConvert(
            M origin,
            boolean caseSensitive,
            Function<String, String> duplicateErrMsg,
            Supplier<M> mapSupplier) {
        if (caseSensitive) {
            return origin;
        } else {
            M newMap = mapSupplier.get();
            for (Map.Entry<String, T> entry : origin.entrySet()) {
                String key = entry.getKey();
                checkArgument(!newMap.containsKey(key.toLowerCase()), duplicateErrMsg.apply(key));
                newMap.put(key.toLowerCase(), entry.getValue());
            }
            return newMap;
        }
    }

    public static Function<String, String> columnDuplicateErrMsg(String tableName) {
        return column ->
                String.format(
                        "Failed to convert columns of table '%s' to case-insensitive form because duplicate column found: '%s'.",
                        tableName, column);
    }

    public static Function<String, String> recordKeyDuplicateErrMsg(Map<String, String> record) {
        return column ->
                "Failed to convert record map to case-insensitive form because duplicate column found. Original record map is:\n"
                        + record;
    }

    public static List<String> listCaseConvert(List<String> origin, boolean caseSensitive) {
        return caseSensitive
                ? origin
                : origin.stream().map(String::toLowerCase).collect(Collectors.toList());
    }

    public static Schema buildPaimonSchema(
            List<String> specifiedPartitionKeys,
            List<String> specifiedPrimaryKeys,
            List<ComputedColumn> computedColumns,
            Map<String, String> tableConfig,
            LinkedHashMap<String, DataType> sourceColumns,
            @Nullable List<String> sourceColumnComments,
            List<String> sourcePrimaryKeys) {
        Schema.Builder builder = Schema.newBuilder();

        // options
        builder.options(tableConfig);

        // columns
        if (sourceColumnComments != null) {
            checkArgument(
                    sourceColumns.size() == sourceColumnComments.size(),
                    "Source table columns count and column comments count should be equal.");

            int i = 0;
            for (Map.Entry<String, DataType> entry : sourceColumns.entrySet()) {
                builder.column(entry.getKey(), entry.getValue(), sourceColumnComments.get(i++));
            }
        } else {
            sourceColumns.forEach(builder::column);
        }

        for (ComputedColumn computedColumn : computedColumns) {
            builder.column(computedColumn.columnName(), computedColumn.columnType());
        }

        // primary keys
        if (!specifiedPrimaryKeys.isEmpty()) {
            for (String key : specifiedPrimaryKeys) {
                if (!sourceColumns.containsKey(key)
                        && computedColumns.stream().noneMatch(c -> c.columnName().equals(key))) {
                    throw new IllegalArgumentException(
                            "Specified primary key '"
                                    + key
                                    + "' does not exist in source tables or computed columns.");
                }
            }
            builder.primaryKey(specifiedPrimaryKeys);
        } else if (!sourcePrimaryKeys.isEmpty()) {
            builder.primaryKey(sourcePrimaryKeys);
        } else {
            throw new IllegalArgumentException(
                    "Primary keys are not specified. "
                            + "Also, can't infer primary keys from source table schemas because "
                            + "source tables have no primary keys or have different primary keys.");
        }

        // partition keys
        if (!specifiedPartitionKeys.isEmpty()) {
            builder.partitionKeys(specifiedPartitionKeys);
        }

        return builder.build();
    }

    public static String tableList(
            MultiTablesSinkMode mode,
            String databasePattern,
            String includingTablePattern,
            List<Identifier> monitoredTables,
            List<Identifier> excludedTables) {
        if (mode == DIVIDED) {
            return dividedModeTableList(monitoredTables);
        } else if (mode == COMBINED) {
            return combinedModeTableList(databasePattern, includingTablePattern, excludedTables);
        }

        throw new UnsupportedOperationException("Unknown MultiTablesSinkMode: " + mode);
    }

    private static String dividedModeTableList(List<Identifier> monitoredTables) {
        // In DIVIDED mode, we only concern about existed tables
        return monitoredTables.stream()
                .map(t -> t.getDatabaseName() + "\\." + t.getObjectName())
                .collect(Collectors.joining("|"));
    }

    public static String combinedModeTableList(
            String databasePattern, String includingTablePattern, List<Identifier> excludedTables) {
        // In COMBINED mode, we should consider both existed tables
        // and possible newly created
        // tables, so we should use regular expression to monitor all valid tables and exclude
        // certain invalid tables

        // The table list is built by template:
        // (?!(^db\\.tbl$)|(^...$))((databasePattern)\\.(including_pattern1|...))

        // The excluding pattern ?!(^db\\.tbl$)|(^...$) can exclude tables whose qualified name
        // is exactly equal to 'db.tbl'
        // The including pattern (databasePattern)\\.(including_pattern1|...) can include tables
        // whose qualified name matches one of the patterns

        // a table can be monitored only when its name meets the including pattern and doesn't
        // be excluded by excluding pattern at the same time
        String includingPattern =
                String.format("(%s)\\.(%s)", databasePattern, includingTablePattern);

        if (excludedTables.isEmpty()) {
            return includingPattern;
        }

        String excludingPattern =
                excludedTables.stream()
                        .map(
                                t ->
                                        String.format(
                                                "(^%s$)",
                                                t.getDatabaseName() + "\\." + t.getObjectName()))
                        .collect(Collectors.joining("|"));
        excludingPattern = "?!" + excludingPattern;
        return String.format("(%s)(%s)", excludingPattern, includingPattern);
    }
}
