package codes.draeger.ktserve.cors

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class CorsTest {
    @Test
    fun explicitPolicyChecksOriginsMethodsAndRequestedHeaders() {
        val policy = CorsConfiguration().apply {
            allowOrigins("https://app.example")
            allowMethods("GET", "PATCH")
            allowHeaders("Content-Type", "X-Client", "X-Key")
        }.build()
        val origin = listOf("https://app.example")
        val method = listOf("PATCH")
        assertEquals("https://app.example", policy.preflight(origin, method, listOf("Content-TYPE, x-client")))
        assertEquals("https://app.example", policy.preflight(origin, method, listOf("content-type", "X-Client")))
        assertNull(policy.preflight(listOf("https://other.example"), method, emptyList()))
        assertNull(policy.preflight(origin, listOf("patch"), emptyList()))
        assertNull(policy.preflight(origin, method, listOf("x-client, authorization")))
        assertNull(policy.preflight(origin, method, listOf("x-client, ")))
        assertNull(policy.preflight(origin, method, listOf("x-Key")))
        assertFalse(policy.allowCredentials)
    }

    @Test
    fun duplicateSingletonFieldsDoNotGrantAccess() {
        val policy = CorsConfiguration().apply {
            allowOrigins("https://app.example")
        }.build()
        val origin = listOf("https://app.example")
        assertNull(policy.preflight(origin + origin, listOf("POST"), emptyList()))
        assertNull(policy.preflight(origin, listOf("POST", "POST"), emptyList()))
        assertNull(policy.allowedOrigin(listOf("https://app.example, https://other.example"), "GET"))
        assertNull(policy.allowedOrigin(emptyList(), "GET"))
    }

    @Test
    fun wildcardIsExplicitAndCannotEnableCredentials() {
        val closed = CorsConfiguration().build()
        assertNull(closed.allowedOrigin(listOf("https://app.example"), "GET"))
        val wildcard = CorsConfiguration().apply { allowAnyOrigin() }.build()
        assertEquals("*", wildcard.allowedOrigin(listOf("https://app.example"), "GET"))
        assertEquals("*", wildcard.allowedOrigin(listOf("http://[::1]:8080"), "GET"))
        assertEquals("*", wildcard.allowedOrigin(listOf("null"), "HEAD"))
        assertNull(wildcard.allowedOrigin(listOf("*"), "GET"))
        assertNull(wildcard.allowedOrigin(listOf("https://app.example/path"), "GET"))
        assertNull(wildcard.allowedOrigin(listOf("https://app.example:+443"), "GET"))
        assertNull(wildcard.allowedOrigin(listOf("https://app.example"), "PATCH"))

        assertFailsWith<IllegalArgumentException> {
            cors {
                allowAnyOrigin()
                allowCredentials = true
            }
        }
        assertFailsWith<IllegalArgumentException> { cors { allowOrigins("*") } }
        assertFailsWith<IllegalArgumentException> { cors { allowOrigins("https://app.example/") } }
        assertFailsWith<IllegalArgumentException> { cors { allowMethods("*") } }
        assertFailsWith<IllegalArgumentException> { cors { allowHeaders("X Client") } }
    }
}
