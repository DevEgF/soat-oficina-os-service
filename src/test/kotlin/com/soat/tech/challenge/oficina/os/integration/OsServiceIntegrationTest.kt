package com.soat.tech.challenge.oficina.os.integration

import com.soat.tech.challenge.oficina.os.support.TestJwt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OsServiceIntegrationTest : IntegrationTestSupport() {

    @Test
    fun `health is UP with database and broker in readiness`() {
        val health = get("/actuator/health")
        assertEquals(200, health.statusCode())
        assertEquals("UP", body(health)["status"].asText())

        val readiness = get("/actuator/health/readiness")
        assertEquals(200, readiness.statusCode())
        val components = body(readiness)["components"]
        assertEquals("UP", components["db"]["status"].asText())
        assertEquals("UP", components["rabbit"]["status"].asText())
        assertEquals(200, get("/actuator/health/liveness").statusCode())
    }

    @Test
    fun `api info requires a bearer token`() {
        assertEquals(401, get("/api/info").statusCode())
        assertEquals(401, get("/api/info", TestJwt.token(environment = "prod")).statusCode())
    }

    @Test
    fun `api info answers with a valid HS256 token`() {
        val response = get("/api/info", TestJwt.token())

        assertEquals(200, response.statusCode())
        val info = body(response)
        assertEquals("os-service", info["service"].asText())
        assertEquals("hml", info["environment"].asText())
        assertEquals("0.0.1", info["version"].asText())
        assertNotNull(response.headers().firstValue("X-Correlation-Id").orElse(null))
    }

    @Test
    fun `openapi document is public and lists the new routes`() {
        val response = get("/v3/api-docs")

        assertEquals(200, response.statusCode())
        val paths = body(response)["paths"]
        listOf(
            "/api/info",
            "/api/public/auth/login",
            "/api/work-orders",
            "/api/work-orders/{id}",
            "/api/work-orders/{id}/history",
            "/api/work-orders/{id}/delivery",
            "/api/internal/customers/{taxId}",
        ).forEach { assertTrue(paths.has(it), it) }
    }

    @Test
    fun `routes outside the public and api prefixes are denied`() {
        assertEquals(401, get("/internal").statusCode())
        assertEquals(403, get("/internal", TestJwt.token()).statusCode())
    }

    @Test
    fun `staff login issues a token accepted by the api`() {
        val response = post("/api/public/auth/login", """{"username":"tecnico","password":"tecnico"}""")

        assertEquals(200, response.statusCode())
        val login = body(response)
        assertEquals(3600, login["expiresInSeconds"].asLong())
        assertEquals(setOf("accessToken", "expiresInSeconds"), login.fieldNames().asSequence().toSet())
        assertEquals(200, get("/api/info", login["accessToken"].asText()).statusCode())
    }

    @Test
    fun `staff login rejects wrong credentials and invalid bodies`() {
        assertEquals(401, post("/api/public/auth/login", """{"username":"admin","password":"wrong"}""").statusCode())
        assertEquals(401, post("/api/public/auth/login", """{"username":"ghost","password":"ghost"}""").statusCode())
        assertEquals(400, post("/api/public/auth/login", """{"username":"","password":""}""").statusCode())
        assertEquals(400, post("/api/public/auth/login", "not json").statusCode())
    }
}
