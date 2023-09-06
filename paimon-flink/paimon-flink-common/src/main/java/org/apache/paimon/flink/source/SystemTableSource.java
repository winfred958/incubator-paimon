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

package org.apache.paimon.flink.source;

import org.apache.paimon.flink.FlinkConnectorOptions;
import org.apache.paimon.options.Options;
import org.apache.paimon.predicate.Predicate;
import org.apache.paimon.table.DataTable;
import org.apache.paimon.table.Table;
import org.apache.paimon.table.source.ReadBuilder;

import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.connector.source.Source;
import org.apache.flink.table.connector.ChangelogMode;
import org.apache.flink.table.connector.source.LookupTableSource;
import org.apache.flink.table.connector.source.ScanTableSource.ScanContext;
import org.apache.flink.table.connector.source.ScanTableSource.ScanRuntimeProvider;
import org.apache.flink.table.connector.source.SourceProvider;
import org.apache.flink.table.data.RowData;
import org.apache.flink.table.plan.stats.TableStats;

import javax.annotation.Nullable;

/** A {@link FlinkTableSource} for system table. */
public class SystemTableSource extends FlinkTableSource {

    private final boolean isStreamingMode;
    private final int splitBatchSize;
    private final FlinkConnectorOptions.SplitAssignMode splitAssignMode;

    public SystemTableSource(Table table, boolean isStreamingMode) {
        super(table);
        this.isStreamingMode = isStreamingMode;
        Options options = Options.fromMap(table.options());
        this.splitBatchSize = options.get(FlinkConnectorOptions.SCAN_SPLIT_ENUMERATOR_BATCH_SIZE);
        this.splitAssignMode = options.get(FlinkConnectorOptions.SCAN_SPLIT_ENUMERATOR_ASSIGN_MODE);
    }

    public SystemTableSource(
            Table table,
            boolean isStreamingMode,
            @Nullable Predicate predicate,
            @Nullable int[][] projectFields,
            @Nullable Long limit,
            int splitBatchSize,
            FlinkConnectorOptions.SplitAssignMode splitAssignMode) {
        super(table, predicate, projectFields, limit);
        this.isStreamingMode = isStreamingMode;
        this.splitBatchSize = splitBatchSize;
        this.splitAssignMode = splitAssignMode;
    }

    @Override
    public ChangelogMode getChangelogMode() {
        return ChangelogMode.insertOnly();
    }

    @Override
    public ScanRuntimeProvider getScanRuntimeProvider(ScanContext scanContext) {
        Source<RowData, ?, ?> source;
        ReadBuilder readBuilder =
                table.newReadBuilder().withProjection(projectFields).withFilter(predicate);

        if (isStreamingMode && table instanceof DataTable) {
            source = new ContinuousFileStoreSource(readBuilder, table.options(), limit);
        } else {
            source = new StaticFileStoreSource(readBuilder, limit, splitBatchSize, splitAssignMode);
        }
        return SourceProvider.of(source);
    }

    @Override
    public SystemTableSource copy() {
        return new SystemTableSource(
                table,
                isStreamingMode,
                predicate,
                projectFields,
                limit,
                splitBatchSize,
                splitAssignMode);
    }

    @Override
    public String asSummaryString() {
        return "Paimon-SystemTable-Source";
    }

    @Override
    public void pushWatermark(WatermarkStrategy<RowData> watermarkStrategy) {
        throw new UnsupportedOperationException();
    }

    @Override
    public LookupTableSource.LookupRuntimeProvider getLookupRuntimeProvider(
            LookupTableSource.LookupContext context) {
        throw new UnsupportedOperationException();
    }

    @Override
    public TableStats reportStatistics() {
        throw new UnsupportedOperationException();
    }
}
