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

package org.apache.paimon.flink.source.operator;

import org.apache.paimon.data.InternalRow;
import org.apache.paimon.disk.IOManagerImpl;
import org.apache.paimon.flink.FlinkRowData;
import org.apache.paimon.table.source.ReadBuilder;
import org.apache.paimon.table.source.Split;
import org.apache.paimon.table.source.TableRead;
import org.apache.paimon.utils.CloseableIterator;

import org.apache.flink.streaming.api.operators.AbstractStreamOperator;
import org.apache.flink.streaming.api.operators.OneInputStreamOperator;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.table.data.RowData;

/**
 * The operator that reads the {@link Split splits} received from the preceding {@link
 * MonitorFunction}. Contrary to the {@link MonitorFunction} which has a parallelism of 1, this
 * operator can have DOP > 1.
 */
public class ReadOperator extends AbstractStreamOperator<RowData>
        implements OneInputStreamOperator<Split, RowData> {

    private static final long serialVersionUID = 1L;

    private final ReadBuilder readBuilder;

    private transient TableRead read;
    private transient StreamRecord<RowData> reuseRecord;
    private transient FlinkRowData reuseRow;

    public ReadOperator(ReadBuilder readBuilder) {
        this.readBuilder = readBuilder;
    }

    @Override
    public void open() throws Exception {
        super.open();
        IOManagerImpl ioManager =
                new IOManagerImpl(
                        getContainingTask()
                                .getEnvironment()
                                .getIOManager()
                                .getSpillingDirectoriesPaths());
        this.read = readBuilder.newRead().withIOManager(ioManager);
        this.reuseRow = new FlinkRowData(null);
        this.reuseRecord = new StreamRecord<>(reuseRow);
    }

    @Override
    public void processElement(StreamRecord<Split> record) throws Exception {
        try (CloseableIterator<InternalRow> iterator =
                read.createReader(record.getValue()).toCloseableIterator()) {
            while (iterator.hasNext()) {
                reuseRow.replace(iterator.next());
                output.collect(reuseRecord);
            }
        }
    }
}
