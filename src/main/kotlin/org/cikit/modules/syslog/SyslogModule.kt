package org.cikit.modules.syslog

import com.typesafe.config.Config
import io.vertx.core.Vertx
import org.cikit.core.Collector
import org.cikit.core.ModuleAdapter

class SyslogModule : ModuleAdapter("syslog") {
    override val collectorFactory: ((Vertx, Config) -> Collector)? =
            { vertx, config -> SyslogCollector(vertx, SyslogConfig(config)) }
}