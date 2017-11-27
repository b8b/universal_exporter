# Universal Exporter

Collection of metrics exporters for prometheus.
 
## Motivation

Often it is convenient to run an exporter instance
per monitored service instance so that the exporter
can communicate tightly (and securely) with the 
service and export it's metrics. 

However, there are several situations where it is
more convenient to run a single exporter instance
that it capable of connecting to multiple service
instances.

 * monitoring of unmodifiable applicance like systems
 * monitoring a high number of service instances

## Running with gradle 

    cp universal_exporter.conf.sample universal_exporter.con
    ./gradlew run

## Configure prometheus

    - job_name: 'rabbitmq'
      metrics_path: '/metrics/rabbitmq/dev'
      honor_labels: true
      static_configs:
        - targets:
          - localhost:8080

## Roadmap

### Implement additional exporters

 * haproxy
 * nginx
 * ktor
 
### Implement additional output formats

 * ganglia xml
 * nagios
 * csv

### Aggregated metrics endpoint

 * export all instances via /metrics/_collector_
 * export all metrics via /metrics

