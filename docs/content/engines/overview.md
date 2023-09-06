---
title: "Overview"
weight: 1
type: docs
aliases:
- /engines/overview.html
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

# Overview

Paimon not only supports Flink SQL writes and queries natively,
but also provides queries from other popular engines, such as
Apache Spark and Apache Hive.

## Compatibility Matrix

| Engine |    Version    | Batch Read | Batch Write | Create Table | Alter Table | Streaming Write | Streaming Read | Batch Overwrite |
|:------:|:-------------:|:----------:|:-----------:|:------------:|:-----------:|:---------------:|:--------------:|:---------------:|
| Flink  |  1.14 - 1.17  |     ✅      |      ✅      |      ✅       |  ✅(1.17+)   |        ✅        |       ✅        |        ✅        |
| Spark  |   3.1 - 3.4   |     ✅      |      ✅      |      ✅       |      ✅      |        ❌        |       ❌        |        ❌        |
|  Hive  |   2.1 - 3.1   |     ✅      |      ✅      |      ✅       |      ❌      |        ❌        |       ❌        |        ❌        |
| Spark  |      2.4      |     ✅      |      ❌      |      ❌       |      ❌      |        ❌        |       ❌        |        ❌        |
| Trino  |   358 - 422   |     ✅      |      ❌      |      ✅       |      ✅      |        ❌        |       ❌        |        ❌        |
| Presto | 0.236 - 0.280 |     ✅      |      ❌      |      ✅       |      ✅      |        ❌        |       ❌        |        ❌        |

Ongoing engines:
- Doris: Under development, [Support Paimon catalog](https://github.com/apache/doris/issues/18433), [Doris Roadmap 2023](https://github.com/apache/doris/issues/16392).
- Seatunnel: Under development, [Introduce paimon connector](https://github.com/apache/incubator-seatunnel/pull/4178).
- Starrocks: Under discussion

[Download Link]({{< ref "project/download#engine-jars" >}})