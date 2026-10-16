package com.aftercare.aftercareapi.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** {@code aftercare.jobs.*}. */
@ConfigurationProperties("aftercare.jobs")
public record JobProperties(@DefaultValue("true") boolean enabled, @DefaultValue("local") String instanceId) {
}
