package exporter.ganglia

import com.fasterxml.aalto.AsyncXMLStreamReader
import com.fasterxml.aalto.stax.InputFactoryImpl
import exporter.Exporter
import exporter.MetricType
import exporter.MetricWriter
import io.ktor.config.ApplicationConfig
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import kotlinx.coroutines.experimental.io.readUntilDelimiter
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamReader

private class Config(baseConfig: ApplicationConfig, endpointConfig: ApplicationConfig) {
    val host = (endpointConfig.propertyOrNull("host") ?: baseConfig.property("host")).getString()
    val port = (endpointConfig.propertyOrNull("port") ?: baseConfig.property("port")).getString().toInt()
}

private enum class Type {
    String, Int8, Uint8, Int16, Uint16, Int32, Uint32, Float, Double, Timestamp
}

private data class MutableMetricInfo(var gridName: String? = null,
                                     var clusterName: String? = null,
                                     var hostName: String? = null,
                                     var name: String? = null,
                                     var desc: String? = null,
                                     var group: String? = null,
                                     var value: String? = null,
                                     var type: Type = Type.Double)

private fun String.splitToPair(string: String): Pair<String, String>? {
    val i = lastIndexOf(string)
    if (i < 0) {
        return null
    } else {
        return substring(0, i) to substring(i + 1, length)
    }
}

class GangliaExporter(baseConfig: ApplicationConfig, endpointConfigs: List<ApplicationConfig>?) : Exporter {

    override val instance = baseConfig.property("instance").getString()

    private val configList = endpointConfigs?.map { Config(baseConfig, it) } ?: listOf(Config(baseConfig, baseConfig))

    private suspend fun metricValue(writer: MetricWriter, metricInfo: MutableMetricInfo) {
        val metric = (metricInfo.name ?: return) to (metricInfo.value ?: return)
        val fields = mutableListOf<Pair<String, String>>()
        val name: String = when (metricInfo.group) {
            // sflow httpd metrics
            "httpd" -> {
                metricInfo.desc = metricInfo.desc?.substringAfterLast(": ")
                val instAndName = metric.first.splitToPair(".http_")
                if (instAndName == null) {
                    metric.first
                } else {
                    fields.add("httpd" to instAndName.first)
                    instAndName.second
                }
            }
            // hsflowd systemd service metrics
            "vm cpu" -> {
                metricInfo.desc = metricInfo.desc?.substringAfterLast(": ")
                val instAndName = metric.first.splitToPair(".service.vcpu_")
                if (instAndName == null) {
                    metric.first
                } else {
                    fields.add("service" to instAndName.first)
                    instAndName.second.replace('.', '_')
                }
            }
            // hsflowd systemd service metrics
            "vm memory" -> {
                metricInfo.desc = metricInfo.desc?.substringAfterLast(": ")
                val instAndName = metric.first.splitToPair(".service.vmem_")
                if (instAndName == null) {
                    metric.first
                } else {
                    fields.add("service" to instAndName.first)
                    instAndName.second.replace('.', '_')
                }
            }
            // sflow-agent jvm metrics
            "jvm" -> {
                metricInfo.desc = metricInfo.desc?.substringAfterLast(": ")
                val instAndName = metric.first.splitToPair(".jvm_")
                if (instAndName == null) {
                    metric.first
                } else {
                    fields.add("app" to instAndName.first)
                    instAndName.second
                }
            }
            // hsflowd host metrics
            in listOf("cpu", "disk", "load", "memory", "network", "process", "system") -> {
                if (metric.first.startsWith("${metricInfo.group}_")) {
                    "hs_${metric.first}"
                } else {
                    "hs_${metricInfo.group}_${metric.first}"
                }
            }
            // skip other
            else -> ""
        }
        if (metric.first.isBlank() || metric.second.isBlank()) {
            return
        }
        val value: Number = when (metricInfo.type) {
            Type.String -> {
                fields.add("value" to metric.second)
                1
            }
            Type.Float, Type.Double -> {
                metric.second.toDouble()
            }
            else -> {
                metric.second.toLong()
            }
        }
        metricInfo.gridName?.let {
            fields.add("grid" to it)
        }
        metricInfo.clusterName?.let {
            fields.add("cluster" to it)
        }
        metricInfo.hostName?.let {
            fields.add("host" to it)
        }
        writer.metricValue(name, value, MetricType.Gauge, metricInfo.desc, *fields.toTypedArray())
    }

    private suspend fun processEvent(reader: XMLStreamReader, writer: MetricWriter, metricInfo: MutableMetricInfo) {
        when (reader.eventType) {
            XMLStreamConstants.START_ELEMENT -> {
                when (reader.localName) {
                    "GRID" -> {
                        for (i in 0 until reader.attributeCount) {
                            when (reader.getAttributeLocalName(i)) {
                                "NAME" -> metricInfo.gridName = reader.getAttributeValue(i)
                            }
                        }
                    }
                    "CLUSTER" -> {
                        for (i in 0 until reader.attributeCount) {
                            when (reader.getAttributeLocalName(i)) {
                                "NAME" -> metricInfo.clusterName = reader.getAttributeValue(i)
                            }
                        }
                    }
                    "HOST" -> {
                        for (i in 0 until reader.attributeCount) {
                            when (reader.getAttributeLocalName(i)) {
                                "NAME" -> metricInfo.hostName = reader.getAttributeValue(i)
                            }
                        }
                    }
                    "METRIC" -> {
                        var tn = 0L
                        var tmax = -1L
                        for (i in 0 until reader.attributeCount) {
                            when (reader.getAttributeLocalName(i)) {
                                "NAME" -> metricInfo.name = reader.getAttributeValue(i)
                                "VAL" -> metricInfo.value = reader.getAttributeValue(i)
                                "TYPE" -> metricInfo.type = Type.valueOf(reader.getAttributeValue(i).capitalize())
                                "TN" -> tn = reader.getAttributeValue(i).toLong()
                                "TMAX" -> tmax = reader.getAttributeValue(i).toLong()
                            }
                        }
                        if (tn > tmax) {
                            metricInfo.name = null
                        }
                    }
                    "EXTRA_ELEMENT" -> {
                        var extraElementName: String? = null
                        var extraElementVal: String? = null
                        for (i in 0 until reader.attributeCount) {
                            when (reader.getAttributeLocalName(i)) {
                                "NAME" -> extraElementName = reader.getAttributeValue(i)
                                "VAL" -> extraElementVal = reader.getAttributeValue(i)
                            }
                        }
                        when (extraElementName) {
                            "GROUP" -> metricInfo.group = extraElementVal
                            "DESC" -> metricInfo.desc = extraElementVal
                        }
                    }
                }
            }
            XMLStreamConstants.END_ELEMENT -> {
                when (reader.localName) {
                    "GRID" -> metricInfo.gridName = null
                    "CLUSTER" -> metricInfo.clusterName = null
                    "HOST" -> metricInfo.hostName = null
                    "METRIC" -> {
                        metricValue(writer, metricInfo)
                        metricInfo.name = null
                        metricInfo.desc = null
                        metricInfo.group = null
                        metricInfo.type = Type.Double
                        metricInfo.value = null
                    }
                }
            }
        }
    }

    suspend override fun export(writer: MetricWriter) {
        val metricInfo = MutableMetricInfo()

        val remoteAddress = InetSocketAddress(configList.first().host, configList.first().port)
        val socket = aSocket().tcp().connect(remoteAddress)
        try {
            val xif = InputFactoryImpl()
            xif.configureForLowMemUsage()
            val reader = xif.createAsyncForByteArray()
            reader.config.setXmlEncoding("US_ASCII")
            reader.config.setXMLResolver { publicID, systemID, baseURI, namespace -> null }

            val input = socket.openReadChannel()
            val buffer = ByteArray(1024 * 4)

            //skip DTD
            val delimiter = ByteBuffer.wrap("]>".toByteArray(Charsets.US_ASCII))
            val dst = ByteBuffer.wrap(buffer)
            if (input.readUntilDelimiter(delimiter, dst) < dst.limit()) {
                //delimiter found -> skip
                input.readByte()
                input.readByte()
            } else {
                //feed to parser
                reader.inputFeeder.feedInput(buffer, 0, dst.position())
            }

            while (true) {
                val read = input.readAvailable(buffer)
                if (read < 0) {
                    reader.inputFeeder.endOfInput()
                } else {
                    reader.inputFeeder.feedInput(buffer, 0, read)
                }
                while (reader.hasNext()) {
                    val ev = reader.next()
                    if (ev == AsyncXMLStreamReader.EVENT_INCOMPLETE) {
                        break
                    }
                    processEvent(reader, writer, metricInfo)
                }
                if (read < 0) {
                    break
                }
            }
        } finally {
            socket.close()
        }
    }

}
