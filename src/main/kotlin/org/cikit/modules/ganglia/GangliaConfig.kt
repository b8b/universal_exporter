package org.cikit.modules.ganglia

import com.typesafe.config.Config

data class GangliaConfig(val instance: String, val endpoints: List<GangliaEndpoint>) {
    constructor(config: Config) : this(
            config.getString("instance"),
            if (config.hasPath("endpoints")) {
                config.getConfigList("endpoints").map { it.withFallback(config) }
            } else {
                listOf(config)
            }.map(::GangliaEndpoint)
    )
}