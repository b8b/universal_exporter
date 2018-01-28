package org.cikit.modules.rabbitmq

import com.typesafe.config.Config
import java.net.URL
import java.util.*

data class RabbitMQEndpoint(val url: URL, val encodedCredentials: String) {
    constructor(config: Config) : this(
            URL(config.getString("url")),
            Base64.getUrlEncoder().encodeToString("${config.getString("username")}:${config.getString("password")}".toByteArray())
    )
}