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

import com.maxmind.db.NodeCache;
import com.maxmind.geoip2.model.AbstractResponse;
import org.elasticsearch.common.cache.Cache;
import org.elasticsearch.common.cache.CacheBuilder;

import java.net.InetAddress;
import java.util.Objects;
import java.util.function.Function;

/**
 * The in-memory cache for the geoip data. There should only be 1 instance of this class..
 * This cache differs from the maxmind's {@link NodeCache} such that this cache stores the deserialized Json objects to avoid the
 * cost of deserialization for each lookup (cached or not). This comes at slight expense of higher memory usage, but significant
 * reduction of CPU usage.
 */
class GeoIpCache {

    private final Cache<CacheKey, AbstractResponse> cache;

    //package private for testing
    GeoIpCache(long maxSize) {
        if (maxSize < 0) {
            throw new IllegalArgumentException("geoip max cache size must be 0 or greater");
        }
        this.cache = CacheBuilder.<CacheKey, AbstractResponse>builder().setMaximumWeight(maxSize).build();
    }

    <T extends AbstractResponse> T putIfAbsent(InetAddress ip, Class<T> responseType,
                                               Function<InetAddress, AbstractResponse> retrieveFunction) {

        //can't use cache.computeIfAbsent due to the elevated permissions for the jackson (run via the cache loader)
        CacheKey<T> cacheKey = new CacheKey<>(ip, responseType);
        //intentionally non-locking for simplicity...it's OK if we re-put the same key/value in the cache during a race condition.
        AbstractResponse response = cache.get(cacheKey);
        if (response == null) {
            response = retrieveFunction.apply(ip);
            cache.put(cacheKey, response);
        }
        return responseType.cast(response);
    }

    //only useful for testing
    <T extends AbstractResponse> T get(InetAddress ip, Class<T> responseType) {
        CacheKey<T> cacheKey = new CacheKey<>(ip, responseType);
        return responseType.cast(cache.get(cacheKey));
    }

    /**
     * The key to use for the cache. Since this cache can span multiple geoip processors that all use different databases, the response
     * type is needed to be included in the cache key. For example, if we only used the IP address as the key the City and ASN the same
     * IP may be in both with different values and we need to cache both. The response type scopes the IP to the correct database
     * provides a means to safely cast the return objects.
     *
     * @param <T> The AbstractResponse type used to scope the key and cast the result.
     */
    private static class CacheKey<T extends AbstractResponse> {

        private final InetAddress ip;
        private final Class<T> responseType;

        private CacheKey(InetAddress ip, Class<T> responseType) {
            this.ip = ip;
            this.responseType = responseType;
        }

        //generated
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            CacheKey<?> cacheKey = (CacheKey<?>) o;
            return Objects.equals(ip, cacheKey.ip) &&
                    Objects.equals(responseType, cacheKey.responseType);
        }

        //generated
        @Override
        public int hashCode() {
            return Objects.hash(ip, responseType);
        }
    }
}
