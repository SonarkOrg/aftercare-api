package com.aftercare.aftercareapi.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/** {@code aftercare.rate-limit.*}: public endpoint token buckets. Defaults: 60/min per IP, 30/min per code, 10 failures then 1/min. */
@ConfigurationProperties("aftercare.rate-limit")
public record RateLimitProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue List<String> trustedProxies,
        @DefaultValue("10000") int maxKeys,
        Bucket perIp,
        Bucket perCode,
        Bucket failedPerIp) {

    public RateLimitProperties {
        trustedProxies = trustedProxies == null ? List.of()
                : trustedProxies.stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
        perIp = perIp == null ? new Bucket(60, 60) : perIp;
        perCode = perCode == null ? new Bucket(30, 30) : perCode;
        failedPerIp = failedPerIp == null ? new Bucket(10, 1) : failedPerIp;
    }

    public record Bucket(int capacity, int refillPerMinute) {
    }
}
