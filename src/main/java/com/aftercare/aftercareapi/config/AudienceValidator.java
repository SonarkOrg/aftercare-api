package com.aftercare.aftercareapi.config;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.Assert;

/**
 * Rejects tokens whose "aud" claim does not contain the configured audience.
 */
public class AudienceValidator implements OAuth2TokenValidator<Jwt> {

    private final String audience;

    public AudienceValidator(String audience) {
        Assert.hasText(audience, "auth0.audience must not be blank");
        this.audience = audience;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        if (jwt.getAudience() != null && jwt.getAudience().contains(audience)) {
            return OAuth2TokenValidatorResult.success();
        }
        return OAuth2TokenValidatorResult.failure(
                new OAuth2Error("invalid_token", "The required audience is missing", null));
    }
}
