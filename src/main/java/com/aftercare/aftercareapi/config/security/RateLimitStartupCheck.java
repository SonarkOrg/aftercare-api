package com.aftercare.aftercareapi.config.security;

import com.aftercare.aftercareapi.config.RateLimitProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/**
 * Behind a reverse proxy every request seems to come from the proxy's address unless the proxy is listed in
 * {@code aftercare.rate-limit.trusted-proxies}; the per-IP limits then throttle all patients together.
 */
public final class RateLimitStartupCheck {

    private static final Logger log = LoggerFactory.getLogger(RateLimitStartupCheck.class);

    private RateLimitStartupCheck() {
    }

    public static boolean needsWarning(RateLimitProperties p, Environment env) {
        return p.enabled() && p.trustedProxies().isEmpty() && !env.acceptsProfiles(Profiles.of("local"));
    }

    public static void check(RateLimitProperties p, Environment env) {
        if (needsWarning(p, env)) {
            log.warn("Public rate limiting is enabled but aftercare.rate-limit.trusted-proxies is empty: behind a reverse "
                    + "proxy all clients share the proxy's address. Set RATE_LIMIT_TRUSTED_PROXIES to the proxy IPs/CIDRs.");
        }
    }
}
