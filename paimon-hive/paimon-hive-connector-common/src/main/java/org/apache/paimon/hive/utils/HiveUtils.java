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

package org.apache.paimon.hive.utils;

import org.apache.paimon.CoreOptions;
import org.apache.paimon.catalog.CatalogContext;
import org.apache.paimon.hive.LocationKeyExtractor;
import org.apache.paimon.hive.SearchArgumentToPredicateConverter;
import org.apache.paimon.options.Options;
import org.apache.paimon.predicate.Predicate;
import org.apache.paimon.schema.TableSchema;
import org.apache.paimon.table.FileStoreTable;
import org.apache.paimon.table.FileStoreTableFactory;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hive.ql.io.sarg.ConvertAstToSearchArg;
import org.apache.hadoop.hive.ql.io.sarg.SearchArgument;
import org.apache.hadoop.hive.serde2.ColumnProjectionUtils;
import org.apache.hadoop.mapred.JobConf;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/** Utils for create {@link FileStoreTable} and {@link Predicate}. */
public class HiveUtils {

    private static final String PAIMON_PREFIX = "paimon.";

    public static FileStoreTable createFileStoreTable(JobConf jobConf) {
        Options options = extractCatalogConfig(jobConf);
        options.set(CoreOptions.PATH, LocationKeyExtractor.getPaimonLocation(jobConf));
        CatalogContext catalogContext = CatalogContext.create(options, jobConf);
        return FileStoreTableFactory.create(catalogContext);
    }

    public static Optional<Predicate> createPredicate(
            TableSchema tableSchema, JobConf jobConf, boolean limitToReadColumnNames) {
        SearchArgument sarg = ConvertAstToSearchArg.createFromConf(jobConf);
        if (sarg == null) {
            return Optional.empty();
        }
        SearchArgumentToPredicateConverter converter =
                new SearchArgumentToPredicateConverter(
                        sarg,
                        tableSchema.fieldNames(),
                        tableSchema.logicalRowType().getFieldTypes(),
                        limitToReadColumnNames
                                ? Arrays.stream(ColumnProjectionUtils.getReadColumnNames(jobConf))
                                        .collect(Collectors.toSet())
                                : null);
        return converter.convert();
    }

    /** Extract paimon catalog conf from Hive conf. */
    public static Options extractCatalogConfig(Configuration hiveConf) {
        Map<String, String> configMap = new HashMap<>();

        if (hiveConf != null) {
            for (Map.Entry<String, String> entry : hiveConf) {
                String name = entry.getKey();
                String value = entry.getValue();
                if (name.startsWith(PAIMON_PREFIX) && !"NULL".equalsIgnoreCase(value)) {
                    name = name.substring(PAIMON_PREFIX.length());
                    configMap.put(name, value);
                }
            }
        }
        return Options.fromMap(configMap);
    }
}
