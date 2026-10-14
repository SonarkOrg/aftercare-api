package com.aftercare.aftercareapi.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DevAuthGuardTests {

    private static final String SECRET = "local-dev-only-hs256-secret-0123456789abcdef";

    private static MockEnvironment env() {
        return new MockEnvironment().withProperty(DevAuthGuard.SECRET_PROPERTY, SECRET);
    }

    @Test
    void okCase() {
        MockEnvironment e = env();
        e.setActiveProfiles("local");
        assertDoesNotThrow(() -> DevAuthGuard.verify(e));
    }

    @Test
    void auth0IssuerSetFails() {
        assertThrows(IllegalStateException.class, () -> DevAuthGuard.verify(env().withProperty("AUTH0_ISSUER_URI", "https://x.auth0.com/")));
    }

    @Test
    void blankIssuerIsFine() {
        assertDoesNotThrow(() -> DevAuthGuard.verify(env().withProperty("AUTH0_ISSUER_URI", " ")));
    }

    @Test
    void deployedProfilesFail() {
        for (String p : new String[]{"prod", "staging", "qa"}) {
            MockEnvironment e = env();
            e.setActiveProfiles("local", p);
            assertThrows(IllegalStateException.class, () -> DevAuthGuard.verify(e));
        }
    }

    @Test
    void shortSecretFails() {
        assertThrows(IllegalStateException.class,
                () -> DevAuthGuard.verify(new MockEnvironment().withProperty(DevAuthGuard.SECRET_PROPERTY, "short")));
    }
}
