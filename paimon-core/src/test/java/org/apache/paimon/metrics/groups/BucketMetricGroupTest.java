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

package org.apache.paimon.metrics.groups;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Tests for the {@link BucketMetricGroup}. */
public class BucketMetricGroupTest {
    // ------------------------------------------------------------------------
    //  scope name tests
    // ------------------------------------------------------------------------

    @Test
    public void testGenerateScopeDefault() {
        BucketMetricGroup group =
                BucketMetricGroup.createBucketMetricGroup("myTable", 1, "dt=1", "commit");

        assertThat(group.getAllTags().size()).isEqualTo(3);
        assertThat(group.getAllTags().get("table")).isEqualTo("myTable");
        assertThat(group.getAllTags().get("bucket")).isEqualTo("1");
        assertThat(group.getAllTags().get("partition")).isEqualTo("dt=1");
        assertThat(group.getMetricIdentifier("myMetric", ".")).isEqualTo("commit.myMetric");
        assertThat(group.getGroupName()).isEqualTo("commit");
    }
}
