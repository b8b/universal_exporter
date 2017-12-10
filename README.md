# Universal Exporter

Collection of metrics exporters for prometheus.
 
## Status

Only some metrics of interest are exported. It should be
 easy to add (and remove!) metrics in the implementation.
 It is not a goal to have an exhaustive set of metrics.

* RabbitMQ exporter using admin interface (rabbit plugin)
* HAProxy exporter using CSV status page via http
* Ganglia/hsflowd exporter

## Motivation / Architecture

Often it is convenient to run an exporter instance
 per monitored service instance so that the exporter
 can communicate tightly (and securely) with the 
 service and export it's metrics. 

However, there are several situations where it is
 more convenient to run a single exporter instance
 that is capable of connecting to multiple service
 instances.

* monitoring of unmodifiable applicance like systems
* monitoring a high number of service instances

Running on the JVM, this exporter consumes a lot more 
 resources than alternatives available (e.g. haproxy_exporter).

However, the single threaded architecture together with stream
 processing and chunked encoding make sure the resource 
 consumption stays at the same level regardless of how many 
 instances are monitored.

## Running with gradle 

    cp universal_exporter.conf.sample universal_exporter.con
    ./gradlew run

## Configuration

The file universal_exporter.conf.sample is maintained as
 reference. Sample configs for all exporters are added there.

## Configure prometheus (sample)

    - job_name: 'rabbitmq'
      metrics_path: '/metrics/rabbitmq'
      honor_labels: true
      static_configs:
        - targets:
          - localhost:8080

    - job_name: 'haproxy'
      metrics_path: '/metrics/haproxy'
      honor_labels: true
      static_configs:
        - targets:
          - localhost:8080

## Roadmap

### Implement additional exporters

* memcached
* nginx (low - support sflow via ganglia)

### Implement additional output formats

* ganglia xml
* nagios (low)
* csv (low)

### Aggregated metrics endpoint

* export all metrics via /metrics
