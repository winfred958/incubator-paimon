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
import org.apache.paimon.CoreOptions.ChangelogProducer;
import org.apache.paimon.KeyValue;
import org.apache.paimon.KeyValueFileStore;
import org.apache.paimon.WriteMode;
import org.apache.paimon.data.InternalRow;
import org.apache.paimon.fs.FileIO;
import org.apache.paimon.fs.Path;
import org.apache.paimon.manifest.ManifestCacheFilter;
import org.apache.paimon.mergetree.compact.LookupMergeFunction;
import org.apache.paimon.mergetree.compact.MergeFunctionFactory;
import org.apache.paimon.operation.FileStoreScan;
import org.apache.paimon.operation.KeyValueFileStoreScan;
import org.apache.paimon.operation.Lock;
import org.apache.paimon.options.Options;
import org.apache.paimon.predicate.Predicate;
import org.apache.paimon.reader.RecordReader;
import org.apache.paimon.schema.KeyValueFieldsExtractor;
import org.apache.paimon.schema.TableSchema;
import org.apache.paimon.table.sink.SequenceGenerator;
import org.apache.paimon.table.sink.TableWriteImpl;
import org.apache.paimon.table.source.InnerTableRead;
import org.apache.paimon.table.source.KeyValueTableRead;
import org.apache.paimon.table.source.MergeTreeSplitGenerator;
import org.apache.paimon.table.source.SplitGenerator;
import org.apache.paimon.table.source.ValueContentRowDataRecordIterator;
import org.apache.paimon.types.RowType;

import java.util.List;
import java.util.function.BiConsumer;

import static org.apache.paimon.predicate.PredicateBuilder.and;
import static org.apache.paimon.predicate.PredicateBuilder.pickTransformFieldMapping;
import static org.apache.paimon.predicate.PredicateBuilder.splitAnd;

/** {@link FileStoreTable} for {@link WriteMode#CHANGE_LOG} write mode with primary keys. */
public class ChangelogWithKeyFileStoreTable extends AbstractFileStoreTable {

    private static final long serialVersionUID = 1L;

    private transient KeyValueFileStore lazyStore;

    ChangelogWithKeyFileStoreTable(FileIO fileIO, Path path, TableSchema tableSchema) {
        this(fileIO, path, tableSchema, new CatalogEnvironment(Lock.emptyFactory(), null, null));
    }

    ChangelogWithKeyFileStoreTable(
            FileIO fileIO,
            Path path,
            TableSchema tableSchema,
            CatalogEnvironment catalogEnvironment) {
        super(fileIO, path, tableSchema, catalogEnvironment);
    }

    @Override
    protected FileStoreTable copy(TableSchema newTableSchema) {
        return new ChangelogWithKeyFileStoreTable(fileIO, path, newTableSchema, catalogEnvironment);
    }

    @Override
    public KeyValueFileStore store() {
        if (lazyStore == null) {
            RowType rowType = tableSchema.logicalRowType();
            Options conf = Options.fromMap(tableSchema.options());
            CoreOptions options = new CoreOptions(conf);
            KeyValueFieldsExtractor extractor =
                    ChangelogWithKeyTableUtils.ChangelogWithKeyKeyValueFieldsExtractor.EXTRACTOR;

            MergeFunctionFactory<KeyValue> mfFactory =
                    ChangelogWithKeyTableUtils.createMergeFunctionFactory(tableSchema);
            if (options.changelogProducer() == ChangelogProducer.LOOKUP) {
                mfFactory =
                        LookupMergeFunction.wrap(
                                mfFactory, new RowType(extractor.keyFields(tableSchema)), rowType);
            }

            lazyStore =
                    new KeyValueFileStore(
                            fileIO(),
                            schemaManager(),
                            tableSchema.id(),
                            tableSchema.crossPartitionUpdate(),
                            options,
                            tableSchema.logicalPartitionType(),
                            ChangelogWithKeyTableUtils.addKeyNamePrefix(
                                    tableSchema.logicalBucketKeyType()),
                            new RowType(extractor.keyFields(tableSchema)),
                            rowType,
                            extractor,
                            mfFactory);
        }
        return lazyStore;
    }

    @Override
    public SplitGenerator splitGenerator() {
        return new MergeTreeSplitGenerator(
                store().newKeyComparator(),
                store().options().splitTargetSize(),
                store().options().splitOpenFileCost());
    }

    @Override
    public boolean supportStreamingReadOverwrite() {
        return new CoreOptions(tableSchema.options()).streamingReadOverwrite();
    }

    @Override
    public BiConsumer<FileStoreScan, Predicate> nonPartitionFilterConsumer() {
        return (scan, predicate) -> {
            // currently we can only perform filter push down on keys
            // consider this case:
            //   data file 1: insert key = a, value = 1
            //   data file 2: update key = a, value = 2
            //   filter: value = 1
            // if we perform filter push down on values, data file 1 will be chosen, but data
            // file 2 will be ignored, and the final result will be key = a, value = 1 while the
            // correct result is an empty set
            // TODO support value filter
            List<Predicate> keyFilters =
                    pickTransformFieldMapping(
                            splitAnd(predicate),
                            tableSchema.fieldNames(),
                            tableSchema.trimmedPrimaryKeys());
            if (keyFilters.size() > 0) {
                ((KeyValueFileStoreScan) scan).withKeyFilter(and(keyFilters));
            }
        };
    }

    @Override
    public InnerTableRead innerRead() {
        return new KeyValueTableRead(store().newRead()) {

            @Override
            public InnerTableRead withFilter(Predicate predicate) {
                read.withFilter(predicate);
                return this;
            }

            @Override
            public InnerTableRead withProjection(int[][] projection) {
                read.withValueProjection(projection);
                return this;
            }

            @Override
            protected RecordReader.RecordIterator<InternalRow> rowDataRecordIteratorFromKv(
                    RecordReader.RecordIterator<KeyValue> kvRecordIterator) {
                return new ValueContentRowDataRecordIterator(kvRecordIterator);
            }

            @Override
            public InnerTableRead forceKeepDelete() {
                read.forceKeepDelete();
                return this;
            }
        };
    }

    @Override
    public TableWriteImpl<KeyValue> newWrite(String commitUser) {
        return newWrite(commitUser, null);
    }

    @Override
    public TableWriteImpl<KeyValue> newWrite(
            String commitUser, ManifestCacheFilter manifestFilter) {
        final SequenceGenerator sequenceGenerator =
                SequenceGenerator.create(schema(), store().options());
        final KeyValue kv = new KeyValue();
        return new TableWriteImpl<>(
                store().newWrite(commitUser, manifestFilter),
                createRowKeyExtractor(),
                record -> {
                    long sequenceNumber =
                            sequenceGenerator == null
                                    ? KeyValue.UNKNOWN_SEQUENCE
                                    : sequenceGenerator.generate(record.row());
                    return kv.replace(
                            record.primaryKey(),
                            sequenceNumber,
                            record.row().getRowKind(),
                            record.row());
                });
    }
}
