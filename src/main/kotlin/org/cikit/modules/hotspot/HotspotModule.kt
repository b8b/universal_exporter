package org.cikit.modules.hotspot

import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import org.cikit.core.ModuleAdapter

class HotspotModule : ModuleAdapter("hotspot") {

    override val collectorFactory = { _: Vertx, config: JsonObject ->
        val labels = config.getJsonObject("labels")?.map { o ->
            o.key to o.value.toString()
        }?.toMap() ?: emptyMap()
        HotspotCollector(labels)
    }

}
