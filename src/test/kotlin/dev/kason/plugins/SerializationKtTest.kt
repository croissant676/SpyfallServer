package dev.kason.plugins

import dev.kason.module

import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.server.testing.*
import kotlin.test.Test

class SerializationKtTest {

    @Test
    fun testGetJsonKotlinxserialization() = testApplication {
        application {
            module()
        }
        client.get("/json/kotlinx-serialization").apply {
            TODO("Please write your test here")
        }
    }
}