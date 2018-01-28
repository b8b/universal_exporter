package org.cikit.modules.hotspot

import com.typesafe.config.Config
import io.vertx.core.Vertx
import org.cikit.core.Collector
import org.cikit.core.ModuleAdapter

class HotspotModule : ModuleAdapter("hotspot") {
    override val collectorFactory: ((Vertx, Config) -> Collector)? =
            { vertx, _ -> HotspotCollector(vertx) }
}