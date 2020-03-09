package org.cikit.modules.hotspot

import io.prometheus.client.CollectorRegistry
import io.prometheus.client.hotspot.DefaultExports
import org.cikit.core.Collector
import org.cikit.core.MetricType
import org.cikit.core.MetricValue
import org.cikit.core.MetricWriter

class HotspotCollector(val labels: Map<String, String>) : Collector {

    override val instance: String
        get() = "local"

    init {
        DefaultExports.initialize()
    }

    override suspend fun export(writer: MetricWriter) {
        val samples = CollectorRegistry.defaultRegistry.metricFamilySamples()
        val metricValues = samples.asSequence().flatMap { metric ->
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
                        fields = labels.map { (k, v) -> k to v } + sample.labelNames
                                .mapIndexed { i, k -> k to sample.labelValues[i] })
            }
        }.toList()
        metricValues.forEach {
            writer.metricValue(it)
        }
    }
}
