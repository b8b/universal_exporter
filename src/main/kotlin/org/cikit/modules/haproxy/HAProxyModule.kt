package org.cikit.modules.haproxy

import com.typesafe.config.Config
import io.vertx.core.Vertx
import org.cikit.core.Collector
import org.cikit.core.ModuleAdapter

class HAProxyModule : ModuleAdapter("haproxy") {
    override val collectorFactory: ((Vertx, Config) -> Collector)? =
            { vertx, config -> HAProxyCollector(vertx, HAProxyConfig(config)) }
}