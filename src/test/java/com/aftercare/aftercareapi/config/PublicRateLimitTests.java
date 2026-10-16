package com.aftercare.aftercareapi.config;

import com.aftercare.aftercareapi.Enums.AccessRole;
import com.aftercare.aftercareapi.Services.AccessLinkService;
import com.aftercare.aftercareapi.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

@TestPropertySource(properties = {"aftercare.rate-limit.enabled=true", "aftercare.rate-limit.trusted-proxies=10.0.0.1"})
class PublicRateLimitTests extends IntegrationTest {

    private static RequestPostProcessor from(String ip) {
        return req -> {
            req.setRemoteAddr(ip);
            return req;
        };
    }

    private MvcResult hit(String ip, String code) throws Exception {
        return mockMvc.perform(get("/api/public/q/" + code).with(from(ip))).andReturn();
    }

    private MvcResult hit(String ip, String code, String forwardedFor) throws Exception {
        return mockMvc.perform(get("/api/public/q/" + code).with(from(ip)).header("X-Forwarded-For", forwardedFor)).andReturn();
    }

    private static String unknownCode() {
        return AccessLinkService.generateCode();
    }

    private String validCode() throws Exception {
        long p = newPatient();
        return newFollowUp(p, "VISCERAL_SURGERY", "2026-10-13", null).get("accessCode").asText();
    }

    @Test
    void tenUnknownCodesBlockTheAddressUntilATokenRefills() throws Exception {
        String ip = "198.51.100.1";
        String valid = validCode();
        for (int i = 0; i < 10; i++) {
            assertEquals(404, hit(ip, unknownCode()).getResponse().getStatus(), "attempt " + (i + 1));
        }
        MvcResult blocked = hit(ip, unknownCode());
        assertEquals(429, blocked.getResponse().getStatus());
        int retry = Integer.parseInt(blocked.getResponse().getHeader("Retry-After"));
        assertTrue(retry >= 1 && retry <= 60, "Retry-After " + retry);
        assertEquals("no-store", blocked.getResponse().getHeader("Cache-Control"));
        assertTrue(blocked.getResponse().getContentType().startsWith("application/json"));
        JsonNode body = json(blocked);
        assertEquals(429, body.get("status").asInt());
        assertEquals("RATE_LIMITED", body.get("error").asText());
        assertEquals("Too many requests; try again later", body.get("message").asText());
        assertTrue(body.get("fieldErrors").isObject() && body.get("fieldErrors").isEmpty());
        // even a valid code is refused from that address
        assertEquals(429, hit(ip, valid).getResponse().getStatus());
        // another address is unaffected
        assertEquals(200, hit("198.51.100.2", valid).getResponse().getStatus());
        // a minute later one more attempt is allowed (the valid one succeeds and does not consume)
        clock.plus(Duration.ofMinutes(1));
        assertEquals(200, hit(ip, valid).getResponse().getStatus());
        assertEquals(200, hit(ip, valid).getResponse().getStatus());
        assertEquals(404, hit(ip, unknownCode()).getResponse().getStatus());
        assertEquals(429, hit(ip, valid).getResponse().getStatus(), "the failure consumed the token again");
    }

    @Test
    void successfulRequestsDoNotConsumeTheFailureBucket() throws Exception {
        String valid = validCode();
        for (int i = 0; i < 25; i++) {
            assertEquals(200, hit("198.51.100.3", valid).getResponse().getStatus());
        }
    }

    @Test
    void aSingleCodeIsLimitedTo30RequestsAMinute() throws Exception {
        String ip = "198.51.100.4";
        String valid = validCode();
        for (int i = 0; i < 30; i++) {
            assertEquals(200, hit(ip, valid).getResponse().getStatus(), "request " + (i + 1));
        }
        MvcResult r = hit(ip, valid);
        assertEquals(429, r.getResponse().getStatus());
        assertNotNull(r.getResponse().getHeader("Retry-After"));
        // the limit is on the code, not on the address
        assertEquals(429, hit("198.51.100.5", valid).getResponse().getStatus());
        // another code from the same address is fine
        assertEquals(200, hit(ip, validCode()).getResponse().getStatus());
        // two seconds later one token has come back
        clock.plus(Duration.ofSeconds(2));
        assertEquals(200, hit(ip, valid).getResponse().getStatus());
    }

    @Test
    void anAddressIsLimitedTo60RequestsAMinute() throws Exception {
        String ip = "198.51.100.6";
        String[] codes = {validCode(), validCode(), validCode()};
        for (int i = 0; i < 60; i++) {
            assertEquals(200, hit(ip, codes[i % 3]).getResponse().getStatus(), "request " + (i + 1));
        }
        MvcResult r = hit(ip, codes[0]);
        assertEquals(429, r.getResponse().getStatus());
        assertEquals("1", r.getResponse().getHeader("Retry-After"));
    }

    @Test
    void forwardedForIsIgnoredFromAnUntrustedPeer() throws Exception {
        String peer = "203.0.113.7";
        for (int i = 0; i < 10; i++) {
            assertEquals(404, hit(peer, unknownCode(), "198.51.100." + (100 + i)).getResponse().getStatus());
        }
        // all ten were counted against the peer, whatever the header said
        assertEquals(429, hit(peer, unknownCode(), "198.51.100.200").getResponse().getStatus());
    }

    @Test
    void forwardedForIsHonouredFromATrustedProxy() throws Exception {
        String proxy = "10.0.0.1";
        for (int i = 0; i < 10; i++) {
            assertEquals(404, hit(proxy, unknownCode(), "198.51.100.50").getResponse().getStatus());
        }
        assertEquals(429, hit(proxy, unknownCode(), "198.51.100.50").getResponse().getStatus());
        // a different client behind the same proxy is fine
        assertEquals(404, hit(proxy, unknownCode(), "198.51.100.51").getResponse().getStatus());
        // right to left, skipping trusted proxies; a spoofed leftmost entry does not help
        assertEquals(429, hit(proxy, unknownCode(), "1.2.3.4, 198.51.100.50, 10.0.0.1").getResponse().getStatus());
        // malformed entries are ignored
        assertEquals(429, hit(proxy, unknownCode(), "garbage, 198.51.100.50").getResponse().getStatus());
    }

    @Test
    void theLimitResponseIsVisibleToTheBrowser() throws Exception {
        String ip = "198.51.100.8";
        for (int i = 0; i < 10; i++) {
            hit(ip, unknownCode());
        }
        MvcResult r = mockMvc.perform(get("/api/public/q/" + unknownCode()).with(from(ip)).header("Origin", "http://localhost:4200")).andReturn();
        assertEquals(429, r.getResponse().getStatus());
        assertEquals("http://localhost:4200", r.getResponse().getHeader("Access-Control-Allow-Origin"));
        assertTrue(r.getResponse().getHeader("Access-Control-Expose-Headers").contains("Retry-After"));
    }

    @Test
    void staffEndpointsAreNeverLimited() throws Exception {
        for (int i = 0; i < 80; i++) {
            MvcResult r = mockMvc.perform(get("/api/patients").with(from("198.51.100.9")).with(asRole(AccessRole.NURSE))).andReturn();
            assertEquals(200, r.getResponse().getStatus(), "request " + (i + 1));
        }
        // and a blocked public address can still use the staff API
        String ip = "198.51.100.10";
        for (int i = 0; i < 11; i++) {
            hit(ip, unknownCode());
        }
        assertEquals(429, hit(ip, unknownCode()).getResponse().getStatus());
        assertEquals(200, mockMvc.perform(get("/api/patients").with(from(ip)).with(asRole(AccessRole.NURSE))).andReturn().getResponse().getStatus());
    }

    @Test
    void theCodeIsNeverTheKey() {
        // the per-code bucket key is the SHA-256 of the code (see PublicRateLimitFilter); a pure sanity check of that hash
        assertEquals(64, AccessLinkService.hash(unknownCode()).length());
    }

    @Test
    void addressesOfOneIpv6SlashSixtyFourShareTheLimits() throws Exception {
        for (int i = 0; i < 10; i++) {
            assertEquals(404, hit("2001:db8:77:1::" + Integer.toHexString(i + 1), unknownCode()).getResponse().getStatus());
        }
        // a new address in the same /64 is already blocked; another /64 is not
        assertEquals(429, hit("2001:db8:77:1:abcd::5", unknownCode()).getResponse().getStatus());
        assertEquals(404, hit("2001:db8:77:2::1", unknownCode()).getResponse().getStatus());
    }

}
