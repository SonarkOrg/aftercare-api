package com.aftercare.aftercareapi.Services.notifications;

/** Outcome of one send attempt. Senders return these instead of throwing for provider errors. */
public sealed interface SendResult {

    record Sent(String providerId) implements SendResult {
    }

    /** Worth retrying (network, 5xx, rate limit, authentication outage). */
    record Transient(String code) implements SendResult {
    }

    /** The provider will never accept this message (bad address, blocked number). */
    record Permanent(String code) implements SendResult {
    }
}
