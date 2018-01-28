package org.cikit.core

import com.typesafe.config.Config
import io.vertx.core.Vertx

interface Module {

    val name: String

    val collectorFactory: ((Vertx, Config) -> Collector)?

}

abstract class ModuleAdapter(override val name: String) : Module {
    override val collectorFactory: ((Vertx, Config) -> Collector)?
        get() = null
}