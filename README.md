# elasticsearch-painlesswhitelist-geoip
An geoip whitelisting additional classes and methods in painless


### Install
- Install plugin
    ````
    ./bin/elasticsearch-plugin install https://github.com/shi-yuan/elasticsearch-painlesswhitelist-geoip/releases/download/v6.4.2/elasticsearch-painlesswhitelist-geoip-6.4.2.0-release.zip
    ````
- The geoip config directory is located at $ES_HOME/config/ingest-geoip or $ES_HOME/config/painlesswhitelist-geoip and holds the shipped databases too. you can have a look at [Ingest Geoip Processor Plugin]()https://www.elastic.co/guide/en/elasticsearch/plugins/current/ingest-geoip.html)


### Usage
````
curl -X POST "localhost:9200/test/test/_search?pretty" -H 'Content-Type: application/json' -d'
{
  "query": {
    "match_all": {}
  },
  "script_fields": {
    "test1": {
      "script": "GeoIpProcessor.process(\"27.24.3.88\")"
    }
  }
}
'
````
response:
```json
{
  "took": 5,
  "timed_out": false,
  "_shards": {
    "total": 5,
    "successful": 5,
    "skipped": 0,
    "failed": 0
  },
  "hits": {
    "total": 1,
    "max_score": 1,
    "hits": [
      {
        "_index": "test",
        "_type": "test",
        "_id": "1",
        "_score": 1,
        "fields": {
          "test1": [
            {
              "continent_name": "Asia",
              "region_iso_code": "CN-42",
              "city_name": "Wuhan",
              "country_iso_code": "CN",
              "region_name": "Hubei",
              "location": {
                "lon": 114.2734,
                "lat": 30.5801
              }
            }
          ]
        }
      }
    ]
  }
}
```
