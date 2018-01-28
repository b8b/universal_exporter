[![GitHub License](https://img.shields.io/badge/license-Apache%20License%202.0-blue.svg?style=flat)](http://www.apache.org/licenses/LICENSE-2.0)

# Universal Exporter

Collection of metrics exporters for prometheus.

## Status

* RabbitMQ exporter using admin interface (rabbit plugin)
* HAProxy exporter using CSV status page via http
* Ganglia/hsflowd exporter using gmond tcp socket

## Running with gradle 

    cp universal_exporter.conf.sample universal_exporter.con
    ./gradlew run

## Installing on FreeBSD

    ./gradlew installDist
    cd build/install/universal_exporter
    
    make
    sudo make install
    
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

### Additional collectors

* e.g. jolokia

### Additional deployments

* Docker
* Systemd
