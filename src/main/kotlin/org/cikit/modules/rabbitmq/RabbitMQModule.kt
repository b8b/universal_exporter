package org.cikit.modules.rabbitmq

import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import org.cikit.core.ModuleAdapter
import java.net.URL
import java.util.*

data class RabbitMQConfig(val instance: String, val endpoints: List<RabbitMQEndpoint>)
data class RabbitMQEndpoint(val url: URL, val encodedCredentials: String)

class RabbitMQModule : ModuleAdapter("rabbitmq") {

    override val collectorFactory = { vx: Vertx, config: JsonObject ->
        val instance = config.getString("instance") ?: error("instance not set")
        val defaultUsername = config.getString("username")
        val defaultPassword = config.getString("password")
        val defaultUrl = config.getString("url")
        val defaultEndpoints = if (defaultUrl != null &&
                defaultUsername != null &&
                defaultPassword != null) {
            listOf(RabbitMQEndpoint(
                    url = URL(defaultUrl),
                    encodedCredentials = Base64.getUrlEncoder().encodeToString(
                            "$defaultUsername:$defaultPassword".toByteArray()
                    )
            ))
        } else {
            null
        }
        val rabbitMQConfig = RabbitMQConfig(
                instance = instance,
                endpoints = config.getJsonArray("endpoints")?.map { c ->
                    val endpointConfig = c as JsonObject
                    val url = endpointConfig.getString("url")
                            ?: defaultUrl ?: error("url not set")
                    val username = endpointConfig.getString("username")
                            ?: defaultUsername ?: error("username not set")
                    val password = endpointConfig.getString("password")
                            ?: defaultPassword ?: error("password not set")
                    val encodedCredentials = Base64.getUrlEncoder()
                            .encodeToString("$username:$password".toByteArray())
                    RabbitMQEndpoint(
                            url = URL(url),
                            encodedCredentials = encodedCredentials
                    )
                } ?: defaultEndpoints ?: error("no endpoints configured")
        )
        RabbitMQCollector(vx, rabbitMQConfig)
    }

}
