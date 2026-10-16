package com.aftercare.aftercareapi.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({NotificationProperties.class, RetentionProperties.class, RateLimitProperties.class,
        JobProperties.class})
public class PropertiesConfig {
}
