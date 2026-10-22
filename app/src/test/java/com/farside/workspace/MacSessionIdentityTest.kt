package com.farside.workspace

import org.junit.Assert.*
import org.junit.Test

class MacSessionIdentityTest {
    @Test fun confirmedFullNameSelectsTheSameAccountsShortName() {
        assertEquals("fixture",MacSessionIdentity.canonical("Fixture User","fixture\nFixture User"))
        assertEquals("fixture",MacSessionIdentity.canonical("fixture","fixture\nFixture User"))
    }
    @Test fun unrelatedGatewayOrAmbiguousOutputCannotChangeTheLogin() {
        for(reply in listOf("gateway\nGateway User","fixture\nFixture User\nOther","fixture","../fixture\nFixture User")) {
            assertEquals("Fixture User",MacSessionIdentity.canonical("Fixture User",reply))
        }
    }
}
