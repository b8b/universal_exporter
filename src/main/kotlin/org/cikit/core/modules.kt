package org.cikit.core

import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject

interface Module {

    val name: String

    val collectorFactory: ((Vertx, JsonObject) -> Collector)?

}

abstract class ModuleAdapter(override val name: String) : Module {
    override val collectorFactory: ((Vertx, JsonObject) -> Collector)?
        get() = null
}
