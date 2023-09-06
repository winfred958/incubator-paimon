---
title: "System Tables"
weight: 6
type: docs
aliases:
- /how-to/system-tables.html
---
<!--
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.
-->

# System Tables

## Table Specified System Table

Table specified system tables contain metadata and information about each table, such as the snapshots created and the options in use. Users can access system tables with batch queries.

Currently, Flink, Spark and Trino supports querying system tables.

In some cases, the table name needs to be enclosed with back quotes to avoid syntax parsing conflicts, for example triple access mode:
```sql
SELECT * FROM my_catalog.my_db.`MyTable$snapshots`;
```

### Snapshots Table

You can query the snapshot history information of the table through snapshots table, including the record count occurred in the snapshot.

```sql
SELECT * FROM MyTable$snapshots;

/*
+--------------+------------+-----------------+-------------------+--------------+-------------------------+--------------------------------+------------------------------- +--------------------------------+---------------------+---------------------+-------------------------+-------------------+--------------------+----------------+
|  snapshot_id |  schema_id |     commit_user | commit_identifier |  commit_kind |             commit_time |             base_manifest_list |            delta_manifest_list |        changelog_manifest_list |  total_record_count |  delta_record_count |  changelog_record_count |  added_file_count |  delete_file_count |      watermark |
+--------------+------------+-----------------+-------------------+--------------+-------------------------+--------------------------------+------------------------------- +--------------------------------+---------------------+---------------------+-------------------------+-------------------+--------------------+----------------+
|            2 |          0 | 7ca4cd28-98e... |                 2 |       APPEND | 2022-10-26 11:44:15.600 | manifest-list-31323d5f-76e6... | manifest-list-31323d5f-76e6... | manifest-list-31323d5f-76e6... |                   2 |                   2 |                       0 |                 2 |                  0 |  1666755855600 |
|            1 |          0 | 870062aa-3e9... |                 1 |       APPEND | 2022-10-26 11:44:15.148 | manifest-list-31593d5f-76e6... | manifest-list-31593d5f-76e6... | manifest-list-31593d5f-76e6... |                   1 |                   1 |                       0 |                 1 |                  0 |  1666755855148 |
+--------------+------------+-----------------+-------------------+--------------+-------------------------+--------------------------------+------------------------------- +--------------------------------+---------------------+---------------------+-------------------------+-------------------+--------------------+----------------+
2 rows in set
*/
```

By querying the snapshots table, you can know the commit and expiration information about that table and time travel through the data.

### Schemas Table

You can query the historical schemas of the table through schemas table.

```sql
SELECT * FROM MyTable$schemas;

/*
+-----------+--------------------------------+----------------+--------------+---------+---------+-------------------------+
| schema_id |                         fields | partition_keys | primary_keys | options | comment |       update_time       |
+-----------+--------------------------------+----------------+--------------+---------+---------+-------------------------+
|         0 | [{"id":0,"name":"word","typ... |             [] |     ["word"] |      {} |         | 2022-10-28 11:44:20.600 |
|         1 | [{"id":0,"name":"word","typ... |             [] |     ["word"] |      {} |         | 2022-10-27 11:44:15.600 |
|         2 | [{"id":0,"name":"word","typ... |             [] |     ["word"] |      {} |         | 2022-10-26 11:44:10.600 |
+-----------+--------------------------------+----------------+--------------+---------+---------+-------------------------+
3 rows in set
*/
```

You can join the snapshots table and schemas table to get the fields of given snapshots.

```sql
SELECT s.snapshot_id, t.schema_id, t.fields 
    FROM MyTable$snapshots s JOIN MyTable$schemas t 
    ON s.schema_id=t.schema_id where s.snapshot_id=100;
```

### Options Table

You can query the table's option information which is specified from the DDL through options table. The options not shown will be the default value. You can take reference to [Configuration]({{< ref "maintenance/configurations#coreoptions" >}}).

```sql
SELECT * FROM MyTable$options;

/*
+------------------------+--------------------+
|         key            |        value       |
+------------------------+--------------------+
| snapshot.time-retained |         5 h        |
+------------------------+--------------------+
1 rows in set
*/
```

### Audit log Table

If you need to audit the changelog of the table, you can use the `audit_log` system table. Through `audit_log` table, you can get the `rowkind` column when you get the incremental data of the table. You can use this column for
filtering and other operations to complete the audit.

There are four values for `rowkind`:

- `+I`: Insertion operation.
- `-U`: Update operation with the previous content of the updated row.
- `+U`: Update operation with new content of the updated row.
- `-D`: Deletion operation.

```sql
SELECT * FROM MyTable$audit_log;

/*
+------------------+-----------------+-----------------+
|     rowkind      |     column_0    |     column_1    |
+------------------+-----------------+-----------------+
|        +I        |      ...        |      ...        |
+------------------+-----------------+-----------------+
|        -U        |      ...        |      ...        |
+------------------+-----------------+-----------------+
|        +U        |      ...        |      ...        |
+------------------+-----------------+-----------------+
3 rows in set
*/
```

### Files Table
You can query the files of the table with specific snapshot.

```sql
-- Query the files of latest snapshot
SELECT * FROM MyTable$files;

/*
+-----------+--------+--------------------------------+-------------+-----------+-------+--------------+--------------------+---------+---------+------------------------+-------------------------+-------------------------+---------------------+---------------------+-----------------------+
| partition | bucket |                      file_path | file_format | schema_id | level | record_count | file_size_in_bytes | min_key | max_key |      null_value_counts |         min_value_stats |         max_value_stats | min_sequence_number | max_sequence_number |         creation_time |
+-----------+--------+--------------------------------+-------------+-----------+-------+--------------+--------------------+---------+---------+------------------------+-------------------------+-------------------------+---------------------+---------------------+-----------------------+
|       [3] |      0 | data-8f64af95-29cc-4342-adc... |         orc |         0 |     0 |            1 |                593 |     [c] |     [c] | {cnt=0, val=0, word=0} | {cnt=3, val=33, word=c} | {cnt=3, val=33, word=c} |       1691551246234 |       1691551246637 |2023-02-24T16:06:21.166|
|       [2] |      0 | data-8b369068-0d37-4011-aa5... |         orc |         0 |     0 |            1 |                593 |     [b] |     [b] | {cnt=0, val=0, word=0} | {cnt=2, val=22, word=b} | {cnt=2, val=22, word=b} |       1691551246233 |       1691551246732 |2023-02-24T16:06:21.166|
|       [2] |      0 | data-83aa7973-060b-40b6-8c8... |         orc |         0 |     0 |            1 |                605 |     [d] |     [d] | {cnt=0, val=0, word=0} | {cnt=2, val=32, word=d} | {cnt=2, val=32, word=d} |       1691551246267 |       1691551246798 |2023-02-24T16:06:21.166|
|       [5] |      0 | data-3d304f4a-bcea-44dc-a13... |         orc |         0 |     0 |            1 |                593 |     [c] |     [c] | {cnt=0, val=0, word=0} | {cnt=5, val=51, word=c} | {cnt=5, val=51, word=c} |       1691551246788 |       1691551246152 |2023-02-24T16:06:21.166|
|       [1] |      0 | data-10abb5bc-0170-43ae-b6a... |         orc |         0 |     0 |            1 |                595 |     [a] |     [a] | {cnt=0, val=0, word=0} | {cnt=1, val=11, word=a} | {cnt=1, val=11, word=a} |       1691551246722 |       1691551246273 |2023-02-24T16:06:21.166|
|       [4] |      0 | data-2c9b7095-65b7-4013-a7a... |         orc |         0 |     0 |            1 |                593 |     [a] |     [a] | {cnt=0, val=0, word=0} | {cnt=4, val=12, word=a} | {cnt=4, val=12, word=a} |       1691551246321 |       1691551246109 |2023-02-24T16:06:21.166|
+-----------+--------+--------------------------------+-------------+-----------+-------+--------------+--------------------+---------+---------+------------------------+-------------------------+-------------------------+---------------------+---------------------+-----------------------+
6 rows in set
*/

-- You can also query the files with specific snapshot
SELECT * FROM MyTable$files /*+ OPTIONS('scan.snapshot-id'='1') */;

/*
+-----------+--------+--------------------------------+-------------+-----------+-------+--------------+--------------------+---------+---------+------------------------+-------------------------+-------------------------+---------------------+---------------------+-----------------------+
| partition | bucket |                      file_path | file_format | schema_id | level | record_count | file_size_in_bytes | min_key | max_key |      null_value_counts |         min_value_stats |         max_value_stats | min_sequence_number | max_sequence_number |         creation_time |
+-----------+--------+--------------------------------+-------------+-----------+-------+--------------+--------------------+---------+---------+------------------------+-------------------------+-------------------------+---------------------+---------------------+-----------------------+
|       [3] |      0 | data-8f64af95-29cc-4342-adc... |         orc |         0 |     0 |            1 |                593 |     [c] |     [c] | {cnt=0, val=0, word=0} | {cnt=3, val=33, word=c} | {cnt=3, val=33, word=c} |       1691551246234 |       1691551246637 |2023-02-24T16:06:21.166|
|       [2] |      0 | data-8b369068-0d37-4011-aa5... |         orc |         0 |     0 |            1 |                593 |     [b] |     [b] | {cnt=0, val=0, word=0} | {cnt=2, val=22, word=b} | {cnt=2, val=22, word=b} |       1691551246233 |       1691551246732 |2023-02-24T16:06:21.166|
|       [1] |      0 | data-10abb5bc-0170-43ae-b6a... |         orc |         0 |     0 |            1 |                595 |     [a] |     [a] | {cnt=0, val=0, word=0} | {cnt=1, val=11, word=a} | {cnt=1, val=11, word=a} |       1691551246267 |       1691551246798 |2023-02-24T16:06:21.166|
+-----------+--------+--------------------------------+-------------+-----------+-------+--------------+--------------------+---------+---------+------------------------+-------------------------+-------------------------+---------------------+---------------------+-----------------------+
3 rows in set
*/
```

### Tags Table

You can query the tag history information of the table through tags table, including which snapshots are the tags based on
and some historical information of the snapshots. You can also get all tag names and time travel to a specific tag data by name.

```sql
SELECT * FROM MyTable$tags;

/*
+----------+-------------+-----------+-------------------------+--------------+
| tag_name | snapshot_id | schema_id |             commit_time | record_count |
+----------+-------------+-----------+-------------------------+--------------+
|     tag1 |           1 |         0 | 2023-06-28 14:55:29.344 |            3 |
|     tag3 |           3 |         0 | 2023-06-28 14:58:24.691 |            7 |
+----------+-------------+-----------+-------------------------+--------------+
2 rows in set
*/
```

### Consumers Table

You can query all consumers which contains next snapshot.

```sql
SELECT * FROM MyTable$consumers;

/*
+-------------+------------------+
| consumer_id | next_snapshot_id |
+-------------+------------------+
|         id1 |                1 |
|         id2 |                3 |
+-------------+------------------+
2 rows in set
*/
```

### Manifests Table

You can query all manifest files contained in the latest snapshot or the specified snapshot of the current table.

```sql
-- Query the manifest of latest snapshot
SELECT * FROM MyTable$manifests;

/*
+--------------------------------+-------------+------------------+-------------------+---------------+
|                      file_name |   file_size |  num_added_files | num_deleted_files |     schema_id |
+--------------------------------+-------------+------------------+-------------------+---------------+
| manifest-f4dcab43-ef6b-4713... |        12365|               40 |                 0 |             0 |
| manifest-f4dcab43-ef6b-4713... |        1648 |                1 |                 0 |             0 |
+--------------------------------+-------------+------------------+-------------------+---------------+
2 rows in set
*/

-- You can also query the manifest with specified snapshot
SELECT * FROM MyTable$manifests /*+ OPTIONS('scan.snapshot-id'='1') */;
/*
+--------------------------------+-------------+------------------+-------------------+---------------+
|                      file_name |   file_size |  num_added_files | num_deleted_files |     schema_id |
+--------------------------------+-------------+------------------+-------------------+---------------+
| manifest-f4dcab43-ef6b-4713... |        12365|               40 |                 0 |             0 |
+--------------------------------+-------------+------------------+-------------------+---------------+
1 rows in set
*/
```

## Partitions Table

You can query the partition files of the table.

```sql
SELECT * FROM MyTable$partitions;

/*
+---------------+----------------+--------------------+
|  partition    |   record_count |  file_size_in_bytes|
+---------------+----------------+--------------------+
|  [1]          |           1    |             645    |
+---------------+----------------+--------------------+
*/
```

## Global System Table

Global system tables contain the statistical information of all the tables exists in paimon. For convenient of searching, we create a reference system database called `sys`.
We can display all the global system tables by sql in flink:
```sql
USE sys;
SHOW TABLES;
```

### ALL Options Table
This table is similar to [Options Table]({{< ref "how-to/system-tables#options-table" >}}), but it shows all the table options is all database.

```sql
SELECT * FROM sys.all_table_options;

/*
+---------------+--------------------------------+--------------------------------+------------------+
| database_name |                     table_name |                            key |            value |
+---------------+--------------------------------+--------------------------------+------------------+
|         my_db |                    Orders_orc  |                         bucket |               -1 |
|         my_db |                        Orders2 |                         bucket |               -1 |
|         my_db |                        Orders2 |                     write-mode |      append-only |
|         my_db |                        Orders2 |               sink.parallelism |                7 |
|         my_db |                   StockAnalyze |                     write-mode |       change-log |
|         my_db2|                      OrdersSum |                         bucket |                1 |
|         my_db2|                      OrdersSum |                     write-mode |       change-log |
+---------------+--------------------------------+--------------------------------+------------------+
7 rows in set
*/
```

### Catalog Options Table
You can query the catalog's option information through catalog options table. The options not shown will be the default value. You can take reference to [Configuration]({{< ref "maintenance/configurations#coreoptions" >}}).

```sql
SELECT * FROM sys.catalog_options;

/*
+-----------+---------------------------+
|       key |                     value |
+-----------+---------------------------+
| warehouse | hdfs:///path/to/warehouse |
+-----------+---------------------------+
1 rows in set
*/
```

