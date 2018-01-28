package org.cikit.modules.ganglia

import com.typesafe.config.Config

data class GangliaEndpoint(val host: String, val port: Int) {
    constructor(config: Config) : this(
            config.getString("host"),
            config.getInt("port")
    )
}