package com.aftercare.aftercareapi.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AudienceValidatorTests {

	private static final String AUDIENCE = "https://aftercare-api.test";

	private final AudienceValidator validator = new AudienceValidator(AUDIENCE);

	private static Jwt tokenWithAudience(List<String> aud) {
		Jwt.Builder builder = Jwt.withTokenValue("token")
				.header("alg", "RS256")
				.subject("user")
				.issuedAt(Instant.now())
				.expiresAt(Instant.now().plusSeconds(60));
		if (aud != null) {
			builder.audience(aud);
		} else {
			builder.claim("scope", "x");
		}
		return builder.build();
	}

	@Test
	void tokenWithExpectedAudiencePasses() {
		assertFalse(validator.validate(tokenWithAudience(List.of("other", AUDIENCE))).hasErrors());
	}

	@Test
	void tokenWithWrongAudienceFails() {
		assertTrue(validator.validate(tokenWithAudience(List.of("https://someone-else"))).hasErrors());
	}

	@Test
	void tokenWithoutAudienceFails() {
		assertTrue(validator.validate(tokenWithAudience(null)).hasErrors());
	}

}
