package com.aftercare.aftercareapi.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwtValidatorTests {

	private static final String ISSUER = "https://test-issuer.invalid/";
	private static final String AUDIENCE = "https://aftercare-api.test";

	private final OAuth2TokenValidator<Jwt> validator = SecurityConfig.jwtValidator(ISSUER, AUDIENCE);

	private static Jwt.Builder base() {
		return Jwt.withTokenValue("token")
				.header("alg", "RS256")
				.subject("user")
				.issuer(ISSUER)
				.audience(List.of(AUDIENCE))
				.issuedAt(Instant.now().minusSeconds(10))
				.expiresAt(Instant.now().plusSeconds(300));
	}

	@Test
	void validTokenPasses() {
		assertFalse(validator.validate(base().build()).hasErrors());
	}

	@Test
	void wrongIssuerFails() {
		assertTrue(validator.validate(base().issuer("https://evil.example/").build()).hasErrors());
	}

	@Test
	void wrongAudienceFails() {
		assertTrue(validator.validate(base().audience(List.of("https://other")).build()).hasErrors());
	}

	@Test
	void singleStringAudienceClaimPasses() {
		assertFalse(validator.validate(base().claim("aud", AUDIENCE).build()).hasErrors());
	}

	@Test
	void expiredTokenFails() {
		Jwt expired = base()
				.issuedAt(Instant.now().minusSeconds(7200))
				.expiresAt(Instant.now().minusSeconds(3600))
				.build();
		assertTrue(validator.validate(expired).hasErrors());
	}

}
