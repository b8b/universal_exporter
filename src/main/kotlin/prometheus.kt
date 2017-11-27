import io.ktor.cio.WriteChannel
import io.ktor.cio.toOutputStream
import io.ktor.content.OutgoingContent

class PrometheusOutput(val metricValues: Sequence<MetricValue>) : OutgoingContent.WriteChannelContent() {
    suspend override fun writeTo(channel: WriteChannel) {
        val metricSet = mutableSetOf<String>()
        channel.toOutputStream().use { out ->
            out.bufferedWriter().use { writer ->
                metricValues.forEach { v ->
                    if (v.name !in metricSet) {
                        metricSet.add(v.name)
                        writer.write("# TYPE ${v.name} ${v.type.toString().toLowerCase()}\n")
                        if (v.description != null) {
                            writer.write("# HELP ${v.name} ${v.description}\n")
                        }
                    }
                    writer.write(v.name)
                    if (v.fields != null) {
                        writer.write(v.fields.joinToString(",", "{", "}",
                                transform = { """${it.first}="${it.second}"""" }))
                    }
                    writer.write(" ${v.value}")
                    if (v.ts != null) {
                        writer.write(" ${v.ts}")
                    }
                    writer.newLine()
                }
            }
        }
    }
}
