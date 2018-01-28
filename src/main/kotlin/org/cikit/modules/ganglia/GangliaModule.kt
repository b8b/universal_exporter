package org.cikit.modules.ganglia

import com.typesafe.config.Config
import io.vertx.core.Vertx
import org.cikit.core.Collector
import org.cikit.core.ModuleAdapter

class GangliaModule : ModuleAdapter("ganglia") {
    override val collectorFactory: ((Vertx, Config) -> Collector)? =
            { vertx, config -> GangliaCollector(vertx, GangliaConfig(config)) }
}