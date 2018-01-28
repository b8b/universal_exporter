package org.cikit.core

import kotlinx.html.*
import kotlinx.html.stream.appendHTML

fun Appendable.appendIndex(endpoints: List<String>) {
    appendHTML().html {
        attributes["lang"] = "en"
        head {
            title("Universal Exporter")
        }
        body {
            h1 { +"Universal Exporter" }
            endpoints.forEach { endpoint ->
                p {
                    a(href = endpoint) {
                        +endpoint
                    }
                }
            }
        }
    }
}
