package exporter

import io.ktor.application.call
import io.ktor.html.respondHtml
import io.ktor.routing.Route
import io.ktor.routing.get
import kotlinx.html.*

fun Route.index(exporters: List<String>) {
    get("/") {
        call.respondHtml {
            attributes["lang"] = "en"
            head {
                title("Universal Exporter")
            }
            body {
                h1 { +"Universal Exporter" }
                exporters.forEach {exporter ->
                    p {
                        //TODO get instances from config
                        a(href = "$exporter/{instance}") {
                            +"$exporter/{instance}"
                        }
                    }
                }
            }
        }
    }
}
