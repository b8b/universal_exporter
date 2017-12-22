package exporter

import kotlinx.coroutines.experimental.io.ByteWriteChannel
import kotlinx.coroutines.experimental.io.writeStringUtf8
import java.io.StringWriter

val PROMETHEUS_CONTENT_TYPE = "text/plain; version=0.0.4; charset=utf-8"

fun checkPrometheusMetricName(name: String): Boolean {
    if (name.isEmpty()) {
        return false
    }
    for (index in 0 until name.length) {
        val c = name[index]
        val valid = when (c) {
            'x' -> index == 0 || name[index - 1] != ':'
            in 'a'..'z' -> true
            '_' -> true
            in '0'..'9' -> index != 0
            in 'A'..'Z' -> true
            ':' -> true
            else -> false
        }
        if (!valid) return false
    }
    return true
}

fun checkPrometheusLabelName(name: String): Boolean {
    if (name.isEmpty()) {
        return false
    }
    for (index in 0 until name.length) {
        val c = name[index]
        val valid = when (c) {
            in 'a'..'z' -> true
            '_' -> index != 1 || name[0] != '_'
            in 'A' .. 'Z' -> true
            in '0'..'9' -> index != 0
            else -> false
        }
        if (!valid) return false
    }
    return true
}

fun Number.toPrometheusString(): String {
    val d = toDouble()
    return when {
        d == java.lang.Double.POSITIVE_INFINITY -> "+Inf"
        d == java.lang.Double.NEGATIVE_INFINITY -> "-Inf"
        java.lang.Double.isNaN(d) -> "NaN"
        else -> toString()
    }
}

fun MetricValue.writeAsPrometheusText(writer: Appendable, withHeader: Boolean = false) {
    if (withHeader) {
        writer.append("# TYPE ")
        writer.append(name)
        writer.append(" ")
        writer.append(type.toString().toLowerCase())
        writer.appendln()
        if (description != null) {
            writer.append("# HELP ")
            writer.append(name)
            writer.append(" ")
            for (c in description) {
                when (c) {
                    '\\' -> writer.append("\\\\")
                    '\n' -> writer.append("\\n")
                    else -> writer.append(c)
                }
            }
            writer.appendln()
        }
    }
    writer.append(name)
    if (fields.isNotEmpty()) {
        writer.append("{")
        for (pair in fields) {
            if (!checkPrometheusLabelName(pair.first)) continue
            writer.append(pair.first)
            writer.append("=\"")
            for (c in pair.second) {
                when (c) {
                    '"' -> writer.append("\\\"")
                    '\\' -> writer.append("\\\\")
                    '\n' -> writer.append("\\n")
                    else -> writer.append(c)
                }
            }
            writer.append("\"")
            if (pair != fields.last()) {
                writer.append(",")
            }
        }
        writer.append("}")
    }
    writer.append(" ")
    writer.append(value.toPrometheusString())
    if (ts != null) {
        writer.append(" ")
        writer.append(ts.toString())
    }
    writer.appendln()
}

class PrometheusMetricWriter(private val channel: ByteWriteChannel) : MetricWriter {

    private val metricSet = mutableSetOf<String>()

    suspend override fun metricValue(v: MetricValue) {
        val withHeader = !metricSet.contains(v.name)
        if (withHeader && !checkPrometheusMetricName(v.name)) {
            //skip metric
            return
        }
        StringWriter().let {
            v.writeAsPrometheusText(it, withHeader)
            channel.writeStringUtf8(it.toString())
        }
        if (withHeader) metricSet.add(v.name)
    }

}
