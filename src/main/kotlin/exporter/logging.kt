package exporter

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.PatternLayout
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.encoder.LayoutWrappingEncoder
import ch.qos.logback.core.rolling.RollingFileAppender
import ch.qos.logback.core.rolling.TimeBasedRollingPolicy
import com.typesafe.config.Config
import org.slf4j.LoggerFactory

fun configureLogging(config: Config) {
    val lc = LoggerFactory.getILoggerFactory() as LoggerContext
    val rootLogger = lc.getLogger(Logger.ROOT_LOGGER_NAME)
    rootLogger.level = Level.INFO

    if (config.hasPath("log.file")) {
        val logfilePath = config.getString("log.file")

        val layout = PatternLayout()
        layout.pattern = "%d{\"yyyy-MM-dd'T'HH:mm:ss.SSSXXX\"} wtf [%thread] %-5level %logger{36} - %msg%n"
        layout.context = lc
        layout.start()

        val encoder = LayoutWrappingEncoder<ILoggingEvent>()
        encoder.context = lc
        encoder.layout = layout

        val fa = RollingFileAppender<ILoggingEvent>()
        fa.context = lc
        fa.name = "file"
        fa.encoder = encoder
        fa.file = logfilePath
        val policy = TimeBasedRollingPolicy<ILoggingEvent>()
        policy.context = lc
        policy.fileNamePattern = "${logfilePath}-%d{yyyy-MM-dd}.log"
        policy.setParent(fa)
        policy.start()
        fa.rollingPolicy = policy
        fa.start()
        rootLogger.detachAndStopAllAppenders()
        rootLogger.addAppender(fa)
    }
}
