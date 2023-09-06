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

package org.apache.paimon.catalog;

import org.apache.paimon.annotation.Public;
import org.apache.paimon.factories.Factory;
import org.apache.paimon.factories.FactoryUtil;
import org.apache.paimon.fs.FileIO;
import org.apache.paimon.fs.Path;
import org.apache.paimon.utils.Preconditions;

import java.io.IOException;
import java.io.UncheckedIOException;

import static org.apache.paimon.options.CatalogOptions.METASTORE;
import static org.apache.paimon.options.CatalogOptions.WAREHOUSE;
import static org.apache.paimon.utils.Preconditions.checkArgument;

/**
 * Factory to create {@link Catalog}. Each factory should have a unique identifier.
 *
 * @since 0.4.0
 */
@Public
public interface CatalogFactory extends Factory {

    Catalog create(FileIO fileIO, Path warehouse, CatalogContext context);

    static Path warehouse(CatalogContext context) {
        String warehouse =
                Preconditions.checkNotNull(
                        context.options().get(WAREHOUSE),
                        "Paimon '" + WAREHOUSE.key() + "' path must be set");
        return new Path(warehouse);
    }

    /**
     * If the ClassLoader is not specified, using the context ClassLoader of current thread as
     * default.
     */
    static Catalog createCatalog(CatalogContext options) {
        return createCatalog(options, CatalogFactory.class.getClassLoader());
    }

    static Catalog createCatalog(CatalogContext context, ClassLoader classLoader) {
        // manual validation
        // because different catalog types may have different options
        // we can't list them all in the optionalOptions() method
        String warehouse = warehouse(context).toUri().toString();

        String metastore = context.options().get(METASTORE);
        CatalogFactory catalogFactory =
                FactoryUtil.discoverFactory(classLoader, CatalogFactory.class, metastore);

        Path warehousePath = new Path(warehouse);
        FileIO fileIO;

        try {
            fileIO = FileIO.get(warehousePath, context);
            if (fileIO.exists(warehousePath)) {
                checkArgument(
                        fileIO.isDir(warehousePath),
                        "The %s path '%s' should be a directory.",
                        WAREHOUSE.key(),
                        warehouse);
            } else {
                fileIO.mkdirs(warehousePath);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        return catalogFactory.create(fileIO, warehousePath, context);
    }
}
