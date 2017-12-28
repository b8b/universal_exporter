package exporter

enum class MetricType {
    Counter,
    Gauge,
    Summary,
    Histogram
}

data class MetricValue(
        val name: String,
        val value: Number,
        val type: MetricType,
        val description: String?,
        val ts: Long? = null,
        val fields: List<Pair<String, String>>
)

interface MetricWriter {

    suspend fun metricValue(v: MetricValue)

    suspend fun metricValue(name: String, value: Number, type: MetricType,
                    description: String? = null,
                    ts: Long? = null,
                    vararg fields: Pair<String, String>) =
            metricValue(MetricValue(name, value, type, description, ts, fields.toList()))

    suspend fun metricValue(name: String, value: Number, type: MetricType,
                    description: String? = null,
                    vararg fields: Pair<String, String>) =
            metricValue(MetricValue(name, value, type, description, null, fields.toList()))

}

interface Collector {
    val instance: String
    suspend fun export(writer: MetricWriter)
}
