/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.nlpcn.elasticsearch.painlesswhitelist.geoip;

import com.maxmind.db.NoCache;
import com.maxmind.db.Reader;
import com.maxmind.geoip2.DatabaseReader;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.common.Booleans;
import org.elasticsearch.common.SuppressForbidden;
import org.elasticsearch.common.inject.AbstractModule;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.inject.Module;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.core.internal.io.IOUtils;
import org.elasticsearch.env.Environment;
import org.elasticsearch.plugins.Plugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.security.AccessController;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.*;
import java.util.stream.Stream;

public class GeoipWhitelistPlugin extends Plugin {

    private static final Logger LOGGER = Loggers.getLogger(GeoipWhitelistPlugin.class);

    static Map<String, DatabaseReaderLazyLoader> databaseReaders;

    @Inject
    public static void initDatabaseReaders(Environment env) {
        Path configPath = env.configFile();

        Path geoIpConfigDirectory = configPath.resolve("ingest-geoip");
        LOGGER.info("try to load database file in [{}]", geoIpConfigDirectory);
        if (!Files.exists(geoIpConfigDirectory) && Files.isDirectory(geoIpConfigDirectory)) {
            geoIpConfigDirectory = configPath.resolve("painlesswhitelist-geoip");
            LOGGER.info("try to load database file in [{}]", geoIpConfigDirectory);
            if (!Files.exists(geoIpConfigDirectory) && Files.isDirectory(geoIpConfigDirectory)) {
                throw new IllegalStateException("the geoip directory containing databases doesn't exist");
            }
        }

        try {
            databaseReaders = loadDatabaseReaders(geoIpConfigDirectory);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static Map<String, DatabaseReaderLazyLoader> loadDatabaseReaders(Path geoIpConfigDirectory) throws IOException {
        boolean loadDatabaseOnHeap = Booleans.parseBoolean(System.getProperty("es.geoip.load_db_on_heap", "false"));
        Map<String, DatabaseReaderLazyLoader> databaseReaders = new HashMap<>();
        try (Stream<Path> databaseFiles = Files.list(geoIpConfigDirectory)) {
            PathMatcher pathMatcher = geoIpConfigDirectory.getFileSystem().getPathMatcher("glob:**.mmdb");
            // Use iterator instead of forEach otherwise IOException needs to be caught twice...
            Iterator<Path> iterator = databaseFiles.iterator();
            while (iterator.hasNext()) {
                Path databasePath = iterator.next();
                if (Files.isRegularFile(databasePath) && pathMatcher.matches(databasePath)) {
                    String databaseFileName = databasePath.getFileName().toString();
                    DatabaseReaderLazyLoader holder = new DatabaseReaderLazyLoader(databaseFileName,
                            () -> {
                                DatabaseReader.Builder builder = createDatabaseBuilder(databasePath).withCache(NoCache.getInstance());
                                if (loadDatabaseOnHeap) {
                                    builder.fileMode(Reader.FileMode.MEMORY);
                                } else {
                                    builder.fileMode(Reader.FileMode.MEMORY_MAPPED);
                                }

                                try {
                                    return AccessController.doPrivileged(new PrivilegedExceptionAction<DatabaseReader>() {
                                        @Override
                                        public DatabaseReader run() throws IOException {
                                            return builder.build();
                                        }
                                    });
                                } catch (PrivilegedActionException e) {
                                    throw new IOException(e);
                                }
                            });
                    databaseReaders.put(databaseFileName, holder);
                }
            }
        }

        return Collections.unmodifiableMap(databaseReaders);
    }

    @SuppressForbidden(reason = "Maxmind API requires java.io.File")
    private static DatabaseReader.Builder createDatabaseBuilder(Path databasePath) {
        return new DatabaseReader.Builder(databasePath.toFile());
    }

    @Override
    public Collection<Module> createGuiceModules() {
        return Collections.singletonList(new AbstractModule() {
            @Override
            protected void configure() {
                requestStaticInjection(GeoipWhitelistPlugin.class);
            }
        });
    }

    @Override
    public void close() throws IOException {
        if (databaseReaders != null) {
            IOUtils.close(databaseReaders.values());
        }
    }
}
