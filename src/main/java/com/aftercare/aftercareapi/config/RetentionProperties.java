package com.aftercare.aftercareapi.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** {@code aftercare.retention.*}. The purge job is disabled and dry-run by default. */
@ConfigurationProperties("aftercare.retention")
public record RetentionProperties(@DefaultValue Purge purge, @DefaultValue("12") int auditMinMonths) {

    /** Audit rows are kept at least this many months ({@code aftercare.retention.audit-min-months}, default 12). */
    @ConstructorBinding
    public RetentionProperties {
    }

    public RetentionProperties(Purge purge) {
        this(purge, 12);
    }

    public record Purge(@DefaultValue("false") boolean enabled, @DefaultValue("true") boolean dryRun,
                        @DefaultValue("0 15 3 * * *") String cron, @DefaultValue("500") int batchSize) {
    }
}
