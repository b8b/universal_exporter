package exporter

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.PatternLayout
import ch.qos.logback.classic.filter.LevelFilter
import ch.qos.logback.classic.spi.Configurator
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.ConsoleAppender
import ch.qos.logback.core.encoder.LayoutWrappingEncoder
import ch.qos.logback.core.rolling.RollingFileAppender
import ch.qos.logback.core.spi.ContextAwareBase

class LoggingConfig : ContextAwareBase(), Configurator {

    override fun configure(lc: LoggerContext?) {
        if (lc != null) {
            addInfo("Setting up logging configuration.")

            val layout = PatternLayout();
            layout.setPattern("%d{HH:mm:ss.SSSZ} [%thread] %-5level %logger{36} - %msg%n");
            layout.context = lc
            layout.start()

            val encoder = LayoutWrappingEncoder<ILoggingEvent>()
            encoder.context = lc
            encoder.layout = layout

            val ca = ConsoleAppender<ILoggingEvent>()
            ca.context = lc
            ca.name = "console"
            ca.encoder = encoder
            ca.start()

            val rootLogger = lc.getLogger(Logger.ROOT_LOGGER_NAME)
            rootLogger.addAppender(ca)

            val logfilePath = System.getProperty("logfile.path")
            val logfileLevel = System.getProperty("logfile.level")
            if (logfilePath != null) {
                val fa = RollingFileAppender<ILoggingEvent>()
                fa.context = lc
                fa.name = "file"
                fa.encoder = encoder
                fa.file = logfilePath
                if (logfileLevel != null) {
                    val filter = LevelFilter()
                    filter.setLevel(Level.toLevel(logfileLevel))
                    filter.start()
                    fa.addFilter(filter)
                }
                fa.start()

                rootLogger.addAppender(fa)
            }
        }
    }

}