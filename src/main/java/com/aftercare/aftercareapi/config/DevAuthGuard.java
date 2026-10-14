package com.aftercare.aftercareapi.config;

import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import java.nio.charset.StandardCharsets;

/** Refuses to start dev auth next to a real Auth0 configuration or a deployed-environment profile. */
public final class DevAuthGuard {

    public static final String SECRET_PROPERTY = "aftercare.dev-auth.secret";

    private DevAuthGuard() {
    }

    public static void verify(Environment env) {
        String issuer = env.getProperty("AUTH0_ISSUER_URI");
        if (issuer != null && !issuer.isBlank()) {
            throw new IllegalStateException("Profile 'local' must not be used with AUTH0_ISSUER_URI set");
        }
        if (env.acceptsProfiles(Profiles.of("prod", "staging", "qa"))) {
            throw new IllegalStateException("Profile 'local' must not be combined with prod, staging or qa");
        }
        String secret = env.getProperty(SECRET_PROPERTY);
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("aftercare.dev-auth.secret must be at least 32 bytes");
        }
    }
}
