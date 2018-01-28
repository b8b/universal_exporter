package org.cikit.modules.syslog

import com.typesafe.config.Config

data class SyslogConfig(val port: Int) {
    constructor(config: Config) : this(
            config.getInt("port")
    )
}