package org.cikit.modules.rabbitmq

import com.typesafe.config.Config

data class RabbitMQConfig(val instance: String, val endpoints: List<RabbitMQEndpoint>) {
    constructor(config: Config) : this(
            config.getString("instance"),
            if (config.hasPath("endpoints")) {
                config.getConfigList("endpoints").map { it.withFallback(config) }
            } else {
                listOf(config)
            }.map(::RabbitMQEndpoint)
    )
}