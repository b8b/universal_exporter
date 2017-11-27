import io.ktor.application.call
import io.ktor.html.respondHtml
import io.ktor.routing.Route
import io.ktor.routing.get
import kotlinx.html.*

fun Route.index() {
    get("/") {
        call.respondHtml {
            attributes["lang"] = "en"
            head {
                title("Universal Exporter")
            }
            body {
                h1 { +"Universal Exporter" }
                p {
                    a(href = "/metrics/rabbitmq") {
                        +"RabbitMQ Metrics"
                    }
                }
            }
        }
    }
}
