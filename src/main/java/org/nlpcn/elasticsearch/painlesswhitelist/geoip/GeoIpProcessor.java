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

import com.maxmind.geoip2.DatabaseReader;
import com.maxmind.geoip2.exception.AddressNotFoundException;
import com.maxmind.geoip2.model.AsnResponse;
import com.maxmind.geoip2.model.CityResponse;
import com.maxmind.geoip2.model.CountryResponse;
import com.maxmind.geoip2.record.*;
import org.elasticsearch.ElasticsearchParseException;
import org.elasticsearch.common.network.InetAddresses;
import org.elasticsearch.common.network.NetworkAddress;

import java.net.InetAddress;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.*;

public final class GeoIpProcessor {

    private static final String CITY_DB_SUFFIX = "-City";
    private static final String COUNTRY_DB_SUFFIX = "-Country";
    private static final String ASN_DB_SUFFIX = "-ASN";
    private static final int CACHE_SIZE = 2000;

    private static final Set<Property> DEFAULT_CITY_PROPERTIES = EnumSet.of(
            Property.CONTINENT_NAME, Property.COUNTRY_ISO_CODE, Property.REGION_ISO_CODE,
            Property.REGION_NAME, Property.CITY_NAME, Property.LOCATION
    );
    private static final Set<Property> DEFAULT_COUNTRY_PROPERTIES = EnumSet.of(
            Property.CONTINENT_NAME, Property.COUNTRY_ISO_CODE
    );
    private static final Set<Property> DEFAULT_ASN_PROPERTIES = EnumSet.of(
            Property.IP, Property.ASN, Property.ORGANIZATION_NAME
    );

    private static final GeoIpCache cache = new GeoIpCache(CACHE_SIZE);

    public static Map<String, Object> process(String ip) throws Exception {
        return process(ip, null, null);
    }

    public static Map<String, Object> process(String ip, String databaseFile, String propertyNames) throws Exception {
        if (ip == null) {
            return Collections.emptyMap();
        }

        final InetAddress ipAddress = InetAddresses.forString(ip);

        if (databaseFile == null) {
            databaseFile = "GeoLite2-City.mmdb";
        }

        DatabaseReaderLazyLoader lazyLoader = GeoipWhitelistPlugin.databaseReaders.get(databaseFile);
        if (lazyLoader == null) {
            throw new IllegalArgumentException("database file [" + databaseFile + "] doesn't exist");
        }

        DatabaseReader dbReader = lazyLoader.get();
        String databaseType = dbReader.getMetadata().getDatabaseType();
        Set<Property> properties = parsePropertyNames(databaseType, propertyNames);

        Map<String, Object> geoData;
        if (databaseType.endsWith(CITY_DB_SUFFIX)) {
            try {
                geoData = retrieveCityGeoData(ipAddress, dbReader, properties);
            } catch (AddressNotFoundRuntimeException e) {
                geoData = Collections.emptyMap();
            }
        } else if (databaseType.endsWith(COUNTRY_DB_SUFFIX)) {
            try {
                geoData = retrieveCountryGeoData(ipAddress, dbReader, properties);
            } catch (AddressNotFoundRuntimeException e) {
                geoData = Collections.emptyMap();
            }
        } else if (databaseType.endsWith(ASN_DB_SUFFIX)) {
            try {
                geoData = retrieveAsnGeoData(ipAddress, dbReader, properties);
            } catch (AddressNotFoundRuntimeException e) {
                geoData = Collections.emptyMap();
            }
        } else {
            throw new ElasticsearchParseException("Unsupported database type [" + databaseType + "]", new IllegalStateException());
        }

        return geoData;
    }

    private static Map<String, Object> retrieveCityGeoData(InetAddress ipAddress, DatabaseReader dbReader, Set<Property> properties) {
        CityResponse response = AccessController.doPrivileged((PrivilegedAction<CityResponse>) () ->
                cache.putIfAbsent(ipAddress, CityResponse.class, ip -> {
                    try {
                        return dbReader.city(ip);
                    } catch (AddressNotFoundException e) {
                        throw new AddressNotFoundRuntimeException(e);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }));

        Country country = response.getCountry();
        City city = response.getCity();
        Location location = response.getLocation();
        Continent continent = response.getContinent();
        Subdivision subdivision = response.getMostSpecificSubdivision();

        Map<String, Object> geoData = new HashMap<>();
        for (Property property : properties) {
            switch (property) {
                case IP:
                    geoData.put("ip", NetworkAddress.format(ipAddress));
                    break;
                case COUNTRY_ISO_CODE:
                    String countryIsoCode = country.getIsoCode();
                    if (countryIsoCode != null) {
                        geoData.put("country_iso_code", countryIsoCode);
                    }
                    break;
                case COUNTRY_NAME:
                    String countryName = country.getName();
                    if (countryName != null) {
                        geoData.put("country_name", countryName);
                    }
                    break;
                case CONTINENT_NAME:
                    String continentName = continent.getName();
                    if (continentName != null) {
                        geoData.put("continent_name", continentName);
                    }
                    break;
                case REGION_ISO_CODE:
                    // ISO 3166-2 code for country subdivisions.
                    // See iso.org/iso-3166-country-codes.html
                    String countryIso = country.getIsoCode();
                    String subdivisionIso = subdivision.getIsoCode();
                    if (countryIso != null && subdivisionIso != null) {
                        String regionIsoCode = countryIso + "-" + subdivisionIso;
                        geoData.put("region_iso_code", regionIsoCode);
                    }
                    break;
                case REGION_NAME:
                    String subdivisionName = subdivision.getName();
                    if (subdivisionName != null) {
                        geoData.put("region_name", subdivisionName);
                    }
                    break;
                case CITY_NAME:
                    String cityName = city.getName();
                    if (cityName != null) {
                        geoData.put("city_name", cityName);
                    }
                    break;
                case TIMEZONE:
                    String locationTimeZone = location.getTimeZone();
                    if (locationTimeZone != null) {
                        geoData.put("timezone", locationTimeZone);
                    }
                    break;
                case LOCATION:
                    Double latitude = location.getLatitude();
                    Double longitude = location.getLongitude();
                    if (latitude != null && longitude != null) {
                        Map<String, Object> locationObject = new HashMap<>();
                        locationObject.put("lat", latitude);
                        locationObject.put("lon", longitude);
                        geoData.put("location", locationObject);
                    }
                    break;
            }
        }

        return geoData;
    }

    private static Map<String, Object> retrieveCountryGeoData(InetAddress ipAddress, DatabaseReader dbReader, Set<Property> properties) {
        CountryResponse response = AccessController.doPrivileged((PrivilegedAction<CountryResponse>) () ->
                cache.putIfAbsent(ipAddress, CountryResponse.class, ip -> {
                    try {
                        return dbReader.country(ip);
                    } catch (AddressNotFoundException e) {
                        throw new AddressNotFoundRuntimeException(e);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }));

        Country country = response.getCountry();
        Continent continent = response.getContinent();

        Map<String, Object> geoData = new HashMap<>();
        for (Property property : properties) {
            switch (property) {
                case IP:
                    geoData.put("ip", NetworkAddress.format(ipAddress));
                    break;
                case COUNTRY_ISO_CODE:
                    String countryIsoCode = country.getIsoCode();
                    if (countryIsoCode != null) {
                        geoData.put("country_iso_code", countryIsoCode);
                    }
                    break;
                case COUNTRY_NAME:
                    String countryName = country.getName();
                    if (countryName != null) {
                        geoData.put("country_name", countryName);
                    }
                    break;
                case CONTINENT_NAME:
                    String continentName = continent.getName();
                    if (continentName != null) {
                        geoData.put("continent_name", continentName);
                    }
                    break;
            }
        }

        return geoData;
    }

    private static Map<String, Object> retrieveAsnGeoData(InetAddress ipAddress, DatabaseReader dbReader, Set<Property> properties) {
        AsnResponse response = AccessController.doPrivileged((PrivilegedAction<AsnResponse>) () ->
                cache.putIfAbsent(ipAddress, AsnResponse.class, ip -> {
                    try {
                        return dbReader.asn(ip);
                    } catch (AddressNotFoundException e) {
                        throw new AddressNotFoundRuntimeException(e);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }));

        Integer asn = response.getAutonomousSystemNumber();
        String organization_name = response.getAutonomousSystemOrganization();

        Map<String, Object> geoData = new HashMap<>();
        for (Property property : properties) {
            switch (property) {
                case IP:
                    geoData.put("ip", NetworkAddress.format(ipAddress));
                    break;
                case ASN:
                    if (asn != null) {
                        geoData.put("asn", asn);
                    }
                    break;
                case ORGANIZATION_NAME:
                    if (organization_name != null) {
                        geoData.put("organization_name", organization_name);
                    }
                    break;
            }
        }

        return geoData;
    }

    private static Set<Property> parsePropertyNames(String databaseType, String propertyNames) {
        final Set<Property> properties;
        if (propertyNames != null) {
            properties = EnumSet.noneOf(Property.class);
            for (String fieldName : propertyNames.split(",")) {
                properties.add(Property.parseProperty(databaseType, fieldName));
            }
        } else {
            if (databaseType.endsWith(CITY_DB_SUFFIX)) {
                properties = DEFAULT_CITY_PROPERTIES;
            } else if (databaseType.endsWith(COUNTRY_DB_SUFFIX)) {
                properties = DEFAULT_COUNTRY_PROPERTIES;
            } else if (databaseType.endsWith(ASN_DB_SUFFIX)) {
                properties = DEFAULT_ASN_PROPERTIES;
            } else {
                throw new IllegalArgumentException("Unsupported database type [" + databaseType + "]");
            }
        }

        return properties;
    }

    // Geoip2's AddressNotFoundException is checked and due to the fact that we need run their code
    // inside a PrivilegedAction code block, we are forced to catch any checked exception and rethrow
    // it with an unchecked exception.
    //package private for testing
    static final class AddressNotFoundRuntimeException extends RuntimeException {

        AddressNotFoundRuntimeException(Throwable cause) {
            super(cause);
        }
    }

    enum Property {

        IP,
        COUNTRY_ISO_CODE,
        COUNTRY_NAME,
        CONTINENT_NAME,
        REGION_ISO_CODE,
        REGION_NAME,
        CITY_NAME,
        TIMEZONE,
        LOCATION,
        ASN,
        ORGANIZATION_NAME;

        static final EnumSet<Property> ALL_CITY_PROPERTIES = EnumSet.of(
                Property.IP, Property.COUNTRY_ISO_CODE, Property.COUNTRY_NAME, Property.CONTINENT_NAME,
                Property.REGION_ISO_CODE, Property.REGION_NAME, Property.CITY_NAME, Property.TIMEZONE,
                Property.LOCATION
        );
        static final EnumSet<Property> ALL_COUNTRY_PROPERTIES = EnumSet.of(
                Property.IP, Property.CONTINENT_NAME, Property.COUNTRY_NAME, Property.COUNTRY_ISO_CODE
        );
        static final EnumSet<Property> ALL_ASN_PROPERTIES = EnumSet.of(
                Property.IP, Property.ASN, Property.ORGANIZATION_NAME
        );

        public static Property parseProperty(String databaseType, String value) {
            Set<Property> validProperties = EnumSet.noneOf(Property.class);
            if (databaseType.endsWith(CITY_DB_SUFFIX)) {
                validProperties = ALL_CITY_PROPERTIES;
            } else if (databaseType.endsWith(COUNTRY_DB_SUFFIX)) {
                validProperties = ALL_COUNTRY_PROPERTIES;
            } else if (databaseType.endsWith(ASN_DB_SUFFIX)) {
                validProperties = ALL_ASN_PROPERTIES;
            }

            try {
                Property property = valueOf(value.toUpperCase(Locale.ROOT));
                if (!validProperties.contains(property)) {
                    throw new IllegalArgumentException("invalid");
                }
                return property;
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("illegal property value [" + value + "]. valid values are " +
                        Arrays.toString(validProperties.toArray()));
            }
        }
    }
}
