package exporter

import io.ktor.application.call
import io.ktor.html.respondHtml
import io.ktor.routing.Route
import io.ktor.routing.get
import kotlinx.html.*

fun Route.index(endpoints: List<String>) {
    get("/") {
        call.respondHtml {
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
}
