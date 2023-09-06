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

import org.apache.paimon.types.DataType;
import org.apache.paimon.utils.Preconditions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.apache.paimon.utils.Preconditions.checkArgument;

/** Utility methods for {@link ComputedColumn}, such as build. */
public class ComputedColumnUtils {

    public static List<ComputedColumn> buildComputedColumns(
            List<String> computedColumnArgs, Map<String, DataType> typeMapping) {
        List<ComputedColumn> computedColumns = new ArrayList<>();
        for (String columnArg : computedColumnArgs) {
            String[] kv = columnArg.split("=");
            if (kv.length != 2) {
                throw new IllegalArgumentException(
                        String.format(
                                "Invalid computed column argument: %s. Please use format 'column-name=expr-name(args, ...)'.",
                                columnArg));
            }
            String columnName = kv[0].trim();
            String expression = kv[1].trim();
            // parse expression
            int left = expression.indexOf('(');
            int right = expression.indexOf(')');
            Preconditions.checkArgument(
                    left > 0 && right > left,
                    String.format(
                            "Invalid expression: %s. Please use format 'expr-name(args, ...)'.",
                            expression));

            String exprName = expression.substring(0, left);
            String[] args = expression.substring(left + 1, right).split(",");
            checkArgument(args.length >= 1, "Computed column needs at least one argument.");

            String fieldReference = args[0].trim();
            String[] literals =
                    Arrays.stream(args).skip(1).map(String::trim).toArray(String[]::new);
            checkArgument(
                    typeMapping.containsKey(fieldReference),
                    String.format(
                            "Referenced field '%s' is not in given MySQL fields: %s.",
                            fieldReference, typeMapping.keySet()));

            computedColumns.add(
                    new ComputedColumn(
                            columnName,
                            Expression.create(
                                    exprName,
                                    fieldReference,
                                    typeMapping.get(fieldReference),
                                    literals)));
        }

        return computedColumns;
    }
}
