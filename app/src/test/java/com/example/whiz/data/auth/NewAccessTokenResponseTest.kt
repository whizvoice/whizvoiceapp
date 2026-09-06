package com.example.whiz.data.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Parsing of the /auth/refresh response. The server rotates the refresh token
 * and returns it as an optional field; older servers omit it.
 */
class NewAccessTokenResponseTest {

    @Test
    fun fromJson_readsRotatedRefreshTokenWhenPresent() {
        val json = """{"access_token":"acc","token_type":"bearer","refresh_token":"rot"}"""

        val parsed = NewAccessTokenResponse.fromJson(json)

        assertEquals("acc", parsed.access_token)
        assertEquals("bearer", parsed.token_type)
        assertEquals("rot", parsed.refresh_token)
    }

    @Test
    fun fromJson_leavesRefreshTokenNullWhenServerOmitsIt() {
        val json = """{"access_token":"acc","token_type":"bearer"}"""

        val parsed = NewAccessTokenResponse.fromJson(json)

        assertEquals("acc", parsed.access_token)
        assertNull(parsed.refresh_token)
    }

    @Test
    fun fromJson_treatsJsonNullRefreshTokenAsAbsent() {
        val json = """{"access_token":"acc","refresh_token":null}"""

        val parsed = NewAccessTokenResponse.fromJson(json)

        assertNull(parsed.refresh_token)
    }
}
