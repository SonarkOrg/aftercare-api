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
import com.aftercare.aftercareapi.Exceptions.ApiException;
import com.aftercare.aftercareapi.Repositories.StaffRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Issues HS256 dev tokens. Exists only in profile local. */
@RestController
@Profile("local")
public class DevTokenController {

    private final JwtEncoder encoder;
    private final Clock clock;
    private final String issuer;
    private final String audience;
    private final Duration ttl;
    private final StaffRepository staff;

    static final String DEFAULT_SUB = "dev|admin-1";
    private static final Map<String, String> ROLE_SUBS = Map.of(
            "ADMIN", "dev|admin-1",
            "PHYSICIAN", "auth0|seed-surgeon",
            "NURSE", "dev|nurse-1",
            "RECEPTION", "dev|reception-1");
    private static final Pattern SUB = Pattern.compile("^[A-Za-z0-9|_.:-]{1,64}$");

    public DevTokenController(JwtEncoder encoder, Clock clock, StaffRepository staff,
                              @Value("${aftercare.dev-auth.issuer}") String issuer,
                              @Value("${auth0.audience}") String audience,
                              @Value("${aftercare.dev-auth.token-ttl:PT8H}") Duration ttl) {
        this.encoder = encoder;
        this.staff = staff;
        this.clock = clock;
        this.issuer = issuer;
        this.audience = audience;
        this.ttl = ttl;
    }

    /**
     * {@code ?role=ADMIN|PHYSICIAN|NURSE|RECEPTION} selects the seeded staff login of that role; {@code ?sub=...} issues a token
     * for any subject (an unlinked one gets NOT_STAFF). Without parameters: the seeded admin.
     */
    @GetMapping("/dev/token")
    public ResponseEntity<Map<String, Object>> token(@RequestParam(required = false) String role,
                                                     @RequestParam(required = false) String sub) {
        if (role != null && sub != null) {
            throw ApiException.validation(Map.of("role", "use either role or sub, not both", "sub", "use either role or sub, not both"));
        }
        String subject = DEFAULT_SUB;
        if (role != null) {
            subject = ROLE_SUBS.get(role);
            if (subject == null) {
                throw ApiException.validation("role", "must be ADMIN, PHYSICIAN, NURSE or RECEPTION");
            }
        } else if (sub != null) {
            if (!SUB.matcher(sub).matches()) {
                throw ApiException.validation("sub", "must match [A-Za-z0-9|_.:-]{1,64}");
            }
            subject = sub;
        }
        String name = staff.findByAuth0Subject(subject).map(m -> m.getDisplayName()).orElse(subject);
        Instant now = Instant.now(clock);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .audience(List.of(audience))
                .subject(subject)
                .claim("name", name)
                .issuedAt(now)
                .expiresAt(now.plus(ttl))
                .build();
        String jwt = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accessToken", jwt);
        body.put("tokenType", "Bearer");
        body.put("expiresIn", ttl.toSeconds());
        body.put("sub", subject);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
