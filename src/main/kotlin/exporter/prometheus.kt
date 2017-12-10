package exporter

import io.ktor.cio.WriteChannel
import io.ktor.content.OutgoingContent
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.withCharset
import io.ktor.util.ValuesMap
import kotlinx.coroutines.experimental.io.ByteBuffer
import java.io.BufferedWriter
import java.io.ByteArrayOutputStream

val PROMETHEUS_CONTENT_TYPE = ContentType.Text.Plain
        .withCharset(Charsets.UTF_8)
        .withParameter("version", "0.0.4")

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

fun MetricValue.writeAsPrometheusText(writer: BufferedWriter, withHeader: Boolean = false) {
    if (withHeader) {
        writer.write("# TYPE ")
        writer.write(name)
        writer.write(" ")
        writer.write(type.toString().toLowerCase())
        writer.newLine()
        if (description != null) {
            writer.write("# HELP ")
            writer.write(name)
            writer.write(" ")
            for (c in description) {
                if (c == '\\') {
                    writer.write("\\\\")
                } else if (c == '\n') {
                    writer.write("\\n")
                } else {
                    writer.write(c.toInt())
                }
            }
            writer.newLine()
        }
    }
    writer.write(name)
    if (fields.isNotEmpty()) {
        writer.write("{")
        for (pair in fields) {
            if (!checkPrometheusLabelName(pair.first)) continue
            writer.write(pair.first)
            writer.write("=\"")
            for (c in pair.second) {
                if (c == '"') {
                    writer.write("\\\"")
                } else if (c == '\\') {
                    writer.write("\\\\")
                } else if (c == '\n') {
                    writer.write("\\n")
                } else {
                    writer.write(c.toInt())
                }
            }
            writer.write("\"")
            if (pair != fields.last()) {
                writer.write(",")
            }
        }
        writer.write("}")
    }
    writer.write(" ")
    writer.write(value.toPrometheusString())
    if (ts != null) {
        writer.write(" ")
        writer.write(ts.toString())
    }
    writer.newLine()
}

private class PrometheusMetricWriter(private val channel: WriteChannel) : MetricWriter {

    private val metricSet = mutableSetOf<String>()

    suspend override fun metricValue(v: MetricValue) {
        val withHeader = !metricSet.contains(v.name)
        if (withHeader && !checkPrometheusMetricName(v.name)) {
            //skip metric
            return
        }
        val data = ByteArrayOutputStream().use {
            it.bufferedWriter().use {
                v.writeAsPrometheusText(it, withHeader)
            }
            it.toByteArray()
        }
        if (withHeader) metricSet.add(v.name)
        channel.write(ByteBuffer.wrap(data))
    }

}

class PrometheusOutput(vararg val exporters: Exporter) : OutgoingContent.WriteChannelContent() {

    override val headers: ValuesMap
        get() = ValuesMap.build { contentType(PROMETHEUS_CONTENT_TYPE) }


    suspend override fun writeTo(channel: WriteChannel) {
        channel.use {
            val metricWriter = PrometheusMetricWriter(it)
            exporters.forEach { exporter ->
                exporter.export(metricWriter)
            }
        }
    }

}
