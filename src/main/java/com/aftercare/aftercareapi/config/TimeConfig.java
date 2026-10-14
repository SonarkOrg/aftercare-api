package com.aftercare.aftercareapi.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/** Single business clock (America/Toronto). Instants are stored in UTC. */
@Configuration
public class TimeConfig {

    @Bean
    public Clock clock(@Value("${aftercare.timezone:America/Toronto}") String tz) {
        return Clock.system(ZoneId.of(tz));
    }
}
