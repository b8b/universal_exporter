package org.cikit.modules.ganglia

import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import org.cikit.core.ModuleAdapter

data class GangliaConfig(
        val instance: String,
        val endpoints: List<GangliaEndpoint> = emptyList()
)

data class GangliaEndpoint(
        val host: String,
        val port: Int
)

class GangliaModule : ModuleAdapter("ganglia") {

    override val collectorFactory = { vx: Vertx, config: JsonObject ->
        val instance = config.getString("instance") ?: error("instance not set")
        val defaultHost = config.getString("host") ?: "localhost"
        val defaultPort = config.getInteger("port") ?: 8649
        val gangliaConfig = GangliaConfig(
                instance = instance,
                endpoints = config.getJsonArray("endpoints")?.map { c ->
                    val endpointConfig = c as JsonObject
                    val host = endpointConfig.getString("host") ?: defaultHost
                    val port = endpointConfig.getInteger("port") ?: defaultPort
                    GangliaEndpoint(host, port)
                } ?: listOf(GangliaEndpoint(defaultHost, defaultPort))
        )
        GangliaCollector(vx, gangliaConfig)
    }

}
