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
package org.apache.paimon.spark

import org.apache.paimon.catalog.{Catalog, CatalogContext, CatalogFactory, Identifier}
import org.apache.paimon.options.Options
import org.apache.paimon.spark.catalog.Catalogs
import org.apache.paimon.spark.extensions.PaimonSparkSessionExtensions
import org.apache.paimon.spark.sql.WithTableOptions
import org.apache.paimon.table.AbstractFileStoreTable

import org.apache.spark.paimon.Utils
import org.apache.spark.sql.QueryTest
import org.apache.spark.sql.test.SharedSparkSession
import org.scalactic.source.Position
import org.scalatest.Tag

import java.io.File

class PaimonSparkTestBase extends QueryTest with SharedSparkSession with WithTableOptions {

  protected lazy val tempDBDir: File = Utils.createTempDir

  protected lazy val catalog: Catalog = initCatalog()

  protected val dbName0: String = "test"

  protected val tableName0: String = "T"

  override protected def sparkConf = {
    super.sparkConf
      .set("spark.sql.catalog.paimon", classOf[SparkCatalog].getName)
      .set("spark.sql.catalog.paimon.warehouse", tempDBDir.getCanonicalPath)
      .set("spark.sql.extensions", classOf[PaimonSparkSessionExtensions].getName)
  }

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    spark.sql(s"CREATE DATABASE paimon.$dbName0")
    spark.sql(s"USE paimon.$dbName0")
  }

  override protected def afterAll(): Unit = {
    try {
      spark.sql("USE default")
      spark.sql(s"DROP DATABASE paimon.$dbName0 CASCADE")
    } finally {
      super.afterAll()
    }
  }

  override protected def beforeEach(): Unit = {
    super.beforeAll()
    spark.sql(s"DROP TABLE IF EXISTS $tableName0")
  }

  override def test(testName: String, testTags: Tag*)(testFun: => Any)(implicit
      pos: Position): Unit = {
    println(testName)
    super.test(testName, testTags: _*)(testFun)(pos)
  }

  private def initCatalog(): Catalog = {
    val currentCatalog = spark.sessionState.catalogManager.currentCatalog.name()
    val options = Catalogs.catalogOptions(currentCatalog, spark.sessionState.conf)
    val catalogContext =
      CatalogContext.create(Options.fromMap(options), spark.sessionState.newHadoopConf());
    CatalogFactory.createCatalog(catalogContext);
  }

  def loadTable(tableName: String): AbstractFileStoreTable = {
    catalog.getTable(Identifier.create(dbName0, tableName)).asInstanceOf[AbstractFileStoreTable]
  }
}
