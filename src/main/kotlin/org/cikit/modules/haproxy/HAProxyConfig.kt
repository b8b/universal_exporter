package org.cikit.modules.haproxy

import com.typesafe.config.Config
import java.net.URL

data class HAProxyConfig(val instance: String, val endpoints: List<URL>) {
    constructor(config: Config) : this(
            config.getString("instance"),
            if (config.hasPath("endpoints")) {
                config.getConfigList("endpoints").map { it.withFallback(config) }
            } else {
                listOf(config)
            }.map { URL(it.getString("url")) }
    )
}