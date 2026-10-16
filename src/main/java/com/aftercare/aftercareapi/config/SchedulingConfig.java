package com.aftercare.aftercareapi.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Turns scheduling on. Without it (tests, {@code JOBS_ENABLED=false}) the jobs exist but are never triggered. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "aftercare.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
