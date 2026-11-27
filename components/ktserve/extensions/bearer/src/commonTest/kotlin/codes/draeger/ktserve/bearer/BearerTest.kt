package codes.draeger.ktserve.bearer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame

class BearerTest {
    @Test
    fun extractsAnOpaqueCaseSensitiveTokenUsingACaseInsensitiveScheme() {
        val token = "mF_9.B5f-4.1JqM~+/=="
        val credential = assertIs<BearerCredential.Present>(parseBearerCredential(listOf("bEaReR   $token")))
        assertEquals(token, credential.token)
        assertFalse(credential.toString().contains(token))
    }

    @Test
    fun missingAuthorizationAndOtherSchemesAreAbsent() {
        assertSame(BearerCredential.Absent, parseBearerCredential(emptyList()))
        assertSame(BearerCredential.Absent, parseBearerCredential(listOf("Basic YQ==")))
        assertSame(BearerCredential.Absent, parseBearerCredential(listOf("BearerPlus opaque")))
    }

    @Test
    fun invalidBearerSyntaxIsDistinctFromMissingCredentials() {
        val invalidValues = listOf(
            "", "Bearer", "Bearer ", "Bearer\ttoken", "Bearer \ttoken", "Bearer ==", "Bearer a=b",
            "Bearer a b", "Bearer a,b", "Bearer éclair", "Bearer \"token\"", "Bearer=token",
        )
        for (value in invalidValues) {
            assertSame(BearerCredential.Invalid, parseBearerCredential(listOf(value)), value)
        }
    }

    @Test
    fun repeatedAuthorizationIsAmbiguousEvenWhenValuesAgree() {
        assertSame(BearerCredential.Invalid, parseBearerCredential(listOf("Bearer a", "Bearer a")))
        assertSame(BearerCredential.Invalid, parseBearerCredential(listOf("Basic a", "Bearer b")))
    }
}
