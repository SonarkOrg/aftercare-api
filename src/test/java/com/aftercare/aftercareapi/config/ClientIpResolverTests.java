package com.aftercare.aftercareapi.config;

import com.aftercare.aftercareapi.config.security.ClientIpResolver;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ClientIpResolverTests {

    private static MockHttpServletRequest req(String remote, String xff) {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.setRemoteAddr(remote);
        if (xff != null) {
            r.addHeader("X-Forwarded-For", xff);
        }
        return r;
    }

    @Test
    void withoutTrustedProxiesTheHeaderIsNeverUsed() {
        ClientIpResolver r = new ClientIpResolver(List.of());
        assertEquals("10.0.0.1", r.resolve(req("10.0.0.1", "1.2.3.4")));
    }

    @Test
    void cidrRangesAndTheRightmostUntrustedEntryWin() {
        ClientIpResolver r = new ClientIpResolver(List.of("10.0.0.0/8", "192.168.1.1"));
        assertEquals("1.2.3.4", r.resolve(req("10.20.30.40", "1.2.3.4")));
        assertEquals("5.6.7.8", r.resolve(req("10.20.30.40", "1.2.3.4, 5.6.7.8, 10.1.1.1, 192.168.1.1")));
        assertEquals("10.20.30.40", r.resolve(req("10.20.30.40", null)));
        assertEquals("172.16.0.9", r.resolve(req("172.16.0.9", "1.2.3.4")), "peer outside the trusted ranges");
    }

    @Test
    void allTrustedMeansTheLeftmostAndMalformedEntriesAreIgnored() {
        ClientIpResolver r = new ClientIpResolver(List.of("10.0.0.0/8"));
        assertEquals("10.1.1.1", r.resolve(req("10.9.9.9", "10.1.1.1, 10.2.2.2")));
        assertEquals("10.9.9.9", r.resolve(req("10.9.9.9", "not-an-ip, , ;;")));
        assertEquals("8.8.8.8", r.resolve(req("10.9.9.9", "8.8.8.8, evil.example.com")));
    }

    @Test
    void ipv6AndNamesAreHandledSafely() {
        ClientIpResolver r = new ClientIpResolver(List.of("::1", "10.0.0.1"));
        assertEquals("2001:db8::7", r.resolve(req("::1", "2001:db8::7")));
        assertEquals("203.0.113.9", r.resolve(req("10.0.0.1", "203.0.113.9, localhost")));
    }

    @Test
    void ipv6IsKeyedByItsSlash64() {
        ClientIpResolver r = new ClientIpResolver(List.of());
        String a = r.rateLimitKey(req("2001:db8:1:2:aaaa:bbbb:cccc:dddd", null));
        String b = r.rateLimitKey(req("2001:db8:1:2::1", null));
        String other = r.rateLimitKey(req("2001:db8:1:3::1", null));
        assertEquals("2001:0db8:0001:0002::/64", a);
        assertEquals(a, b, "same /64");
        assertNotEquals(a, other, "next /64");
        assertEquals("2001:0db8:0000:0000::/64", ClientIpResolver.rateLimitKey("2001:db8::7"));
        // IPv4, IPv4-mapped IPv6 and non-literals are untouched / plain IPv4
        assertEquals("10.0.0.1", r.rateLimitKey(req("10.0.0.1", null)));
        assertEquals("203.0.113.9", ClientIpResolver.rateLimitKey("::ffff:203.0.113.9"));
        assertEquals("unknown", ClientIpResolver.rateLimitKey("unknown"));
        // behind a trusted proxy the forwarded client is keyed by its prefix too
        ClientIpResolver proxied = new ClientIpResolver(List.of("10.0.0.1"));
        assertEquals("2001:0db8:0000:0000::/64", proxied.rateLimitKey(req("10.0.0.1", "2001:db8::99")));
        // resolve() itself still returns the full address
        assertEquals("2001:db8::99", proxied.resolve(req("10.0.0.1", "2001:db8::99")));
    }

}
