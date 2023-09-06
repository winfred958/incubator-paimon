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

package org.apache.paimon.flink.sink;

import org.apache.paimon.CoreOptions.MergeEngine;
import org.apache.paimon.flink.LogicalTypeConversion;
import org.apache.paimon.flink.PredicateConverter;
import org.apache.paimon.flink.log.LogStoreTableFactory;
import org.apache.paimon.operation.FileStoreCommit;
import org.apache.paimon.options.Options;
import org.apache.paimon.predicate.AllPrimaryKeyEqualVisitor;
import org.apache.paimon.predicate.OnlyPartitionKeyEqualVisitor;
import org.apache.paimon.predicate.Predicate;
import org.apache.paimon.predicate.PredicateBuilder;
import org.apache.paimon.table.AbstractFileStoreTable;
import org.apache.paimon.table.AppendOnlyFileStoreTable;
import org.apache.paimon.table.ChangelogValueCountFileStoreTable;
import org.apache.paimon.table.ChangelogWithKeyFileStoreTable;
import org.apache.paimon.table.Table;
import org.apache.paimon.table.TableUtils;
import org.apache.paimon.table.sink.BatchWriteBuilder;

import org.apache.flink.table.catalog.Column;
import org.apache.flink.table.catalog.ObjectIdentifier;
import org.apache.flink.table.connector.RowLevelModificationScanContext;
import org.apache.flink.table.connector.sink.DynamicTableSink;
import org.apache.flink.table.connector.sink.abilities.SupportsDeletePushDown;
import org.apache.flink.table.connector.sink.abilities.SupportsRowLevelDelete;
import org.apache.flink.table.connector.sink.abilities.SupportsRowLevelUpdate;
import org.apache.flink.table.expressions.ResolvedExpression;
import org.apache.flink.table.factories.DynamicTableFactory;
import org.apache.flink.table.types.logical.RowType;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.apache.paimon.CoreOptions.MERGE_ENGINE;

/** Table sink to create sink. */
public class FlinkTableSink extends FlinkTableSinkBase
        implements SupportsRowLevelUpdate, SupportsRowLevelDelete, SupportsDeletePushDown {

    @Nullable protected Predicate deletePredicate;

    public FlinkTableSink(
            ObjectIdentifier tableIdentifier,
            Table table,
            DynamicTableFactory.Context context,
            @Nullable LogStoreTableFactory logStoreTableFactory) {
        super(tableIdentifier, table, context, logStoreTableFactory);
    }

    @Override
    public DynamicTableSink copy() {
        FlinkTableSink copied =
                new FlinkTableSink(tableIdentifier, table, context, logStoreTableFactory);
        copied.staticPartitions = new HashMap<>(staticPartitions);
        copied.overwrite = overwrite;
        copied.deletePredicate = deletePredicate;
        return copied;
    }

    @Override
    public RowLevelUpdateInfo applyRowLevelUpdate(
            List<Column> updatedColumns, @Nullable RowLevelModificationScanContext context) {
        // Since only UPDATE_AFTER type messages can be received at present,
        // AppendOnlyFileStoreTable and ChangelogValueCountFileStoreTable without primary keys
        // cannot correctly handle old data, so they are marked as unsupported. Similarly, it is not
        // allowed to update the primary key column when updating the column of
        // ChangelogWithKeyFileStoreTable, because the old data cannot be handled correctly.
        if (table instanceof ChangelogWithKeyFileStoreTable) {
            Options options = Options.fromMap(table.options());
            Set<String> primaryKeys = new HashSet<>(table.primaryKeys());
            updatedColumns.forEach(
                    column -> {
                        if (primaryKeys.contains(column.getName())) {
                            String errMsg =
                                    String.format(
                                            "Updates to primary keys are not supported, primaryKeys (%s), updatedColumns (%s)",
                                            primaryKeys,
                                            updatedColumns.stream()
                                                    .map(Column::getName)
                                                    .collect(Collectors.toList()));
                            throw new UnsupportedOperationException(errMsg);
                        }
                    });
            if (options.get(MERGE_ENGINE) == MergeEngine.DEDUPLICATE
                    || options.get(MERGE_ENGINE) == MergeEngine.PARTIAL_UPDATE) {
                // Even with partial-update we still need all columns. Because the topology
                // structure is source -> cal -> constraintEnforcer -> sink, in the
                // constraintEnforcer operator, the constraint check will be performed according to
                // the index, not according to the column. So we can't return only some columns,
                // which will cause problems like ArrayIndexOutOfBoundsException.
                // TODO: return partial columns after FLINK-32001 is resolved.
                return new RowLevelUpdateInfo() {};
            }
            throw new UnsupportedOperationException(
                    String.format(
                            "%s can not support update, currently only %s of %s and %s can support update.",
                            options.get(MERGE_ENGINE),
                            MERGE_ENGINE.key(),
                            MergeEngine.DEDUPLICATE,
                            MergeEngine.PARTIAL_UPDATE));
        } else if (table instanceof AppendOnlyFileStoreTable
                || table instanceof ChangelogValueCountFileStoreTable) {
            throw new UnsupportedOperationException(
                    String.format(
                            "%s can not support update, because there is no primary key.",
                            table.getClass().getName()));
        } else {
            throw new UnsupportedOperationException(
                    String.format(
                            "%s can not support update, because it is an unknown subclass of FileStoreTable.",
                            table.getClass().getName()));
        }
    }

    @Override
    public RowLevelDeleteInfo applyRowLevelDelete(
            @Nullable RowLevelModificationScanContext rowLevelModificationScanContext) {
        validateDeletable();
        return new RowLevelDeleteInfo() {};
    }

    // supported filters push down please refer DeletePushDownVisitorTest

    @Override
    public boolean applyDeleteFilters(List<ResolvedExpression> list) {
        validateDeletable();
        List<Predicate> predicates = new ArrayList<>();
        RowType rowType = LogicalTypeConversion.toLogicalType(table.rowType());
        for (ResolvedExpression filter : list) {
            Optional<Predicate> predicate = PredicateConverter.convert(rowType, filter);
            if (predicate.isPresent()) {
                predicates.add(predicate.get());
            } else {
                // convert failed, leave it to flink
                return false;
            }
        }
        deletePredicate = predicates.isEmpty() ? null : PredicateBuilder.and(predicates);
        return canPushDownDeleteFilter();
    }

    @Override
    public Optional<Long> executeDeletion() {
        FileStoreCommit commit =
                ((AbstractFileStoreTable) table).store().newCommit(UUID.randomUUID().toString());
        long identifier = BatchWriteBuilder.COMMIT_IDENTIFIER;
        if (deletePredicate == null) {
            commit.purgeTable(identifier);
            return Optional.empty();
        } else if (deleteIsDropPartition()) {
            commit.dropPartitions(Collections.singletonList(deletePartitions()), identifier);
            return Optional.empty();
        } else {
            return Optional.of(
                    TableUtils.deleteWhere(table, Collections.singletonList(deletePredicate)));
        }
    }

    private void validateDeletable() {
        if (table instanceof ChangelogWithKeyFileStoreTable) {
            Options options = Options.fromMap(table.options());
            if (options.get(MERGE_ENGINE) == MergeEngine.DEDUPLICATE) {
                return;
            }
            throw new UnsupportedOperationException(
                    String.format(
                            "merge engine '%s' can not support delete, currently only %s can support delete.",
                            options.get(MERGE_ENGINE), MergeEngine.DEDUPLICATE));
        } else if (table instanceof ChangelogValueCountFileStoreTable) {
            // ChangelogValueCountFileStoreTable is OK to be deleted
            return;
        } else if (table instanceof AppendOnlyFileStoreTable) {
            throw new UnsupportedOperationException(
                    String.format(
                            "table '%s' can not support delete, because there is no primary key.",
                            table.getClass().getName()));
        } else {
            throw new UnsupportedOperationException(
                    String.format(
                            "%s can not support delete, because it is an unknown subclass of FileStoreTable.",
                            table.getClass().getName()));
        }
    }

    private boolean canPushDownDeleteFilter() {
        return deletePredicate == null || deleteIsDropPartition() || deleteInSingleNode();
    }

    private boolean deleteIsDropPartition() {
        if (deletePredicate == null) {
            return false;
        }
        return deletePredicate.visit(new OnlyPartitionKeyEqualVisitor(table.partitionKeys()));
    }

    private Map<String, String> deletePartitions() {
        if (deletePredicate == null) {
            return null;
        }
        OnlyPartitionKeyEqualVisitor visitor =
                new OnlyPartitionKeyEqualVisitor(table.partitionKeys());
        deletePredicate.visit(visitor);
        return visitor.partitions();
    }

    private boolean deleteInSingleNode() {
        if (deletePredicate == null) {
            return false;
        }
        return deletePredicate
                .visit(new AllPrimaryKeyEqualVisitor(table.primaryKeys()))
                .containsAll(table.primaryKeys());
    }
}
