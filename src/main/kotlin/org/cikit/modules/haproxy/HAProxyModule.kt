package org.cikit.modules.haproxy

import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import org.cikit.core.ModuleAdapter
import java.net.URL

data class HAProxyConfig(
        val instance: String,
        val labels: Map<String, String>,
        val endpoints: List<URL>
)

class HAProxyModule : ModuleAdapter("haproxy") {

    override val collectorFactory = { vx: Vertx, config: JsonObject ->
        val instance = config.getString("instance") ?: error("instance not set")
        val labels = config.getJsonObject("labels")?.map { o ->
            o.key to o.value.toString()
        }?.toMap() ?: emptyMap()
        val defaultEndpoints = config.getString("url")?.let { listOf(URL(it)) }
        val haProxyConfig = HAProxyConfig(
                instance = instance,
                labels = labels,
                endpoints = config.getJsonArray("endpoints")?.map { c ->
                    val endpointConfig = c as JsonObject
                    URL(endpointConfig.getString("url")
                            ?: error("endpoint url not set"))
                } ?: defaultEndpoints ?: error("no endpoints configured")
        )
        HAProxyCollector(vx, haProxyConfig)
    }

}
