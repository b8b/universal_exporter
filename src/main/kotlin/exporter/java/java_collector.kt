package exporter.java

import exporter.Collector
import exporter.MetricType
import exporter.MetricValue
import exporter.MetricWriter
import io.prometheus.client.CollectorRegistry
import io.prometheus.client.hotspot.DefaultExports
import io.vertx.core.Vertx

class JavaCollector(private val vertx: Vertx) : Collector {

    override val instance: String
        get() = "local"

    init {
        DefaultExports.initialize()
    }

    suspend override fun export(writer: MetricWriter) {
        val metricValues = CollectorRegistry.defaultRegistry.metricFamilySamples().asSequence().flatMap { metric ->
            metric.samples.asSequence().map { sample ->
                MetricValue(
                        metric.name,
                        sample.value,
                        when (metric.type) {
                            io.prometheus.client.Collector.Type.COUNTER -> MetricType.Counter
                            io.prometheus.client.Collector.Type.GAUGE -> MetricType.Gauge
                            io.prometheus.client.Collector.Type.HISTOGRAM -> MetricType.Histogram
                            io.prometheus.client.Collector.Type.SUMMARY -> MetricType.Summary
                            else -> MetricType.Gauge
                        },
                        metric.help,
                        fields = sample.labelNames.mapIndexed { i, k -> k to sample.labelValues[i] })
            }
        }.toList()
        metricValues.forEach {
            writer.metricValue(it)
        }
    }
}
