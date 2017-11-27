enum class MetricType {
    Counter,
    Gauge,
    Summary
}

data class MetricValue(
        val name: String,
        val value: Number,
        val type: MetricType,
        val description: String?,
        val ts: Long? = null,
        val fields: Array<out Pair<String, String>>? = null
)

fun metricValue(name: String, value: Number, type: MetricType,
                description: String? = null,
                ts: Long? = null,
                vararg fields: Pair<String, String>) =
        MetricValue(name, value, type, description, ts, fields)

fun metricValue(name: String, value: Number, type: MetricType,
                description: String? = null,
                vararg fields: Pair<String, String>) =
        MetricValue(name, value, type, description, null, fields)
