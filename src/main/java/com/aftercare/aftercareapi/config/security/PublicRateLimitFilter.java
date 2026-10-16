package com.aftercare.aftercareapi.config.security;

import com.aftercare.aftercareapi.Exceptions.ApiError;
import com.aftercare.aftercareapi.Services.AccessLinkService;
import com.aftercare.aftercareapi.config.RateLimitProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Map;

/**
 * Token-bucket limits on /api/public/**: per IP, per code (keyed by the code's SHA-256, never the code) and failed
 * lookups (404) per IP. State is in memory and bounded; with several instances each enforces its own limits.
 */
public class PublicRateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(PublicRateLimitFilter.class);
    private static final String CODE_PREFIX = "/api/public/q/";

    private final BucketMap<String> perIp;
    private final BucketMap<String> perCode;
    private final BucketMap<String> failedPerIp;
    private final ClientIpResolver resolver;
    private final ObjectMapper mapper;

    public PublicRateLimitFilter(RateLimitProperties p, Clock clock, ObjectMapper mapper) {
        this.perIp = new BucketMap<>(p.maxKeys(), () -> new TokenBucket(p.perIp().capacity(), p.perIp().refillPerMinute(), clock));
        this.perCode = new BucketMap<>(p.maxKeys(), () -> new TokenBucket(p.perCode().capacity(), p.perCode().refillPerMinute(), clock));
        this.failedPerIp = new BucketMap<>(p.maxKeys(), () -> new TokenBucket(p.failedPerIp().capacity(), p.failedPerIp().refillPerMinute(), clock));
        this.resolver = new ClientIpResolver(p.trustedProxies());
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String ip = resolver.rateLimitKey(request);
        TokenBucket failed = failedPerIp.get(ip);
        if (failed.peekEmpty()) {
            reject(response, "failed-per-ip", failed.retryAfterSeconds());
            return;
        }
        TokenBucket.Result byIp = perIp.get(ip).tryConsume();
        if (!byIp.allowed()) {
            reject(response, "per-ip", byIp.retryAfterSeconds());
            return;
        }
        String code = codeOf(request);
        if (code != null && AccessLinkService.CODE_PATTERN.matcher(code).matches()) {
            TokenBucket.Result byCode = perCode.get(AccessLinkService.hash(code)).tryConsume();
            if (!byCode.allowed()) {
                reject(response, "per-code", byCode.retryAfterSeconds());
                return;
            }
        }
        chain.doFilter(request, response);
        if (response.getStatus() == HttpServletResponse.SC_NOT_FOUND) {
            failed.consume();
        }
    }

    private static String codeOf(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (!path.startsWith(CODE_PREFIX)) {
            return null;
        }
        String rest = path.substring(CODE_PREFIX.length());
        int slash = rest.indexOf('/');
        return slash < 0 ? rest : rest.substring(0, slash);
    }

    private void reject(HttpServletResponse response, String bucket, long retryAfter) throws IOException {
        log.debug("Public rate limit hit ({})", bucket);
        response.setStatus(429);
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(Math.max(1, retryAfter)));
        response.setHeader(HttpHeaders.CACHE_CONTROL, CacheControl.noStore().getHeaderValue());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        mapper.writeValue(response.getOutputStream(),
                new ApiError(429, "RATE_LIMITED", "Too many requests; try again later", Map.of()));
    }
}
