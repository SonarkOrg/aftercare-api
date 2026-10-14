package com.aftercare.aftercareapi.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Issues HS256 dev tokens. Exists only in profile local. */
@RestController
@Profile("local")
public class DevTokenController {

    private final JwtEncoder encoder;
    private final Clock clock;
    private final String issuer;
    private final String audience;
    private final Duration ttl;

    public DevTokenController(JwtEncoder encoder, Clock clock,
                              @Value("${aftercare.dev-auth.issuer}") String issuer,
                              @Value("${auth0.audience}") String audience,
                              @Value("${aftercare.dev-auth.token-ttl:PT8H}") Duration ttl) {
        this.encoder = encoder;
        this.clock = clock;
        this.issuer = issuer;
        this.audience = audience;
        this.ttl = ttl;
    }

    @GetMapping("/dev/token")
    public ResponseEntity<Map<String, Object>> token() {
        Instant now = Instant.now(clock);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .audience(List.of(audience))
                .subject("dev|nurse-1")
                .claim("name", "Dev Nurse")
                .issuedAt(now)
                .expiresAt(now.plus(ttl))
                .build();
        String jwt = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(Map.of("accessToken", jwt, "tokenType", "Bearer", "expiresIn", ttl.toSeconds()));
    }
}
