package org.cikit.modules.hotspot

import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import org.cikit.core.ModuleAdapter

class HotspotModule : ModuleAdapter("hotspot") {

    override val collectorFactory = { _: Vertx, _: JsonObject ->
        HotspotCollector()
    }

}
