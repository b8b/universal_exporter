package org.cikit.modules.rabbitmq

import com.typesafe.config.Config
import io.vertx.core.Vertx
import org.cikit.core.Collector
import org.cikit.core.ModuleAdapter

class RabbitMQModule : ModuleAdapter("rabbitmq") {
    override val collectorFactory: ((Vertx, Config) -> Collector)? =
            { vertx, config -> RabbitMQCollector(vertx, RabbitMQConfig(config)) }
}