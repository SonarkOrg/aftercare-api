package com.aftercare.aftercareapi.config.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.web.util.matcher.IpAddressMatcher;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The client address for rate limiting. X-Forwarded-For is honoured only when the direct peer is a trusted proxy
 * (IPs or CIDRs); then the list is read right to left, skipping trusted proxies. Never relies on a forwarded-headers
 * strategy, so a spoofed header from an untrusted peer is ignored.
 */
public final class ClientIpResolver {

    private static final Pattern IPV4 = Pattern.compile("^(25[0-5]|2[0-4]\\d|1?\\d?\\d)(\\.(25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}$");
    private static final Pattern IPV6 = Pattern.compile("^[0-9a-fA-F:.]{2,45}$");

    private final List<IpAddressMatcher> trusted = new ArrayList<>();

    public ClientIpResolver(List<String> trustedProxies) {
        for (String entry : trustedProxies) {
            String e = entry.trim();
            if (!e.isEmpty()) {
                trusted.add(new IpAddressMatcher(e));
            }
        }
    }

    public String resolve(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        if (remote == null) {
            remote = "unknown";
        }
        if (trusted.isEmpty() || !isTrusted(remote)) {
            return remote;
        }
        String header = request.getHeader("X-Forwarded-For");
        if (header == null || header.isBlank()) {
            return remote;
        }
        List<String> entries = new ArrayList<>();
        for (String part : header.split(",")) {
            String p = part.trim();
            if (isLiteral(p)) {
                entries.add(p);
            }
        }
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (!isTrusted(entries.get(i))) {
                return entries.get(i);
            }
        }
        return entries.isEmpty() ? remote : entries.get(0);
    }

    /** The bucket key for a request: the resolved address, with IPv6 collapsed to its /64 prefix. */
    public String rateLimitKey(HttpServletRequest request) {
        return rateLimitKey(resolve(request));
    }

    /**
     * IPv6 clients usually own a whole /64, so keying by the full address would let one host rotate through
     * addresses to dodge the limits. IPv4 and non-literals are returned unchanged; IPv4-mapped IPv6 is plain IPv4.
     */
    public static String rateLimitKey(String address) {
        if (address == null || address.indexOf(':') < 0 || !isLiteral(address)) {
            return address;
        }
        try {
            // a validated literal: no name lookup can happen
            java.net.InetAddress a = java.net.InetAddress.getByName(address);
            if (a instanceof java.net.Inet4Address) {
                return a.getHostAddress();
            }
            byte[] b = a.getAddress();
            return String.format("%02x%02x:%02x%02x:%02x%02x:%02x%02x::/64",
                    b[0], b[1], b[2], b[3], b[4], b[5], b[6], b[7]);
        } catch (java.net.UnknownHostException | RuntimeException e) {
            return address;
        }
    }

    private boolean isTrusted(String address) {
        if (!isLiteral(address)) {
            return false;
        }
        for (IpAddressMatcher m : trusted) {
            try {
                if (m.matches(address)) {
                    return true;
                }
            } catch (IllegalArgumentException ignored) {
                // address family mismatch: not a match
            }
        }
        return false;
    }

    /** Only IP literals are ever handed to the matcher, so no name lookup can happen. */
    static boolean isLiteral(String s) {
        return IPV4.matcher(s).matches() || (s.indexOf(':') >= 0 && IPV6.matcher(s).matches());
    }
}
