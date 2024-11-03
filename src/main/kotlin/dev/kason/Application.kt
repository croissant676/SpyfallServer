package dev.kason

import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import kotlin.time.Duration.Companion.seconds

fun main() {
    embeddedServer(Netty, port = 8080, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

val spyFallLogger: Logger = LoggerFactory.getLogger("spyFall_global")
lateinit var app: Application

fun Application.module() {
    app = this
    install(CORS) {
        anyMethod()
        anyHost()
    }
    install(ContentNegotiation) {
        json()
    }
    readList()
    configureSockets()
}

val topicAndWordList = mutableListOf<Pair<String, String>>()
fun readList() {
    val text = File("prompts.csv").readLines()
    text.forEach {
        val tokens = it.split(",")
        topicAndWordList.add(Pair(tokens[0], tokens[1]))
    }
    spyFallLogger.info(
        "Parsed words list: ${topicAndWordList.size} items!:: ${
            topicAndWordList.joinToString(limit = 10) { it.first + "/" + it.second }
        }"
    )
}


fun Application.configureSockets() {
    install(WebSockets) {
        pingPeriod = 15.seconds
        timeout = 15.seconds
        maxFrameSize = Long.MAX_VALUE
        masking = false
    }
    routing {
        get("/") {
            call.respond("hello!")
        }
        webSocket("/ws") {
            User(this).initialize()
        }
    }
}

