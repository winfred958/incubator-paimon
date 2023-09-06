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
package org.apache.spark.sql

/**
 * Some classes or methods defined in the spark project are marked as private under
 * [[org.apache.spark.sql]] package, Hence, use this class to adapt then so that we can use them
 * indirectly.
 */
object Utils {

  /**
   * In the streaming write case, An "Queries with streaming sources must be executed with
   * writeStream.start()" error will occur if we transform [[DataFrame]] first and then use it.
   *
   * That's because the new [[DataFrame]] has a streaming source that is not supported, see the
   * detail: SPARK-14473. So we can create a new [[DataFrame]] using the origin, planned
   * [[org.apache.spark.sql.execution.SparkPlan]].
   *
   * By the way, the origin [[DataFrame]] has been planned by
   * [[org.apache.spark.sql.execution.datasources.v2.DataSourceV2Strategy]] before call
   * [[org.apache.spark.sql.execution.streaming.Sink.addBatch]].
   */
  def createNewDataFrame(data: DataFrame): DataFrame = {
    data.sqlContext.internalCreateDataFrame(data.queryExecution.toRdd, data.schema)
  }

}
