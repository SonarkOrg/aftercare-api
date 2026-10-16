package com.aftercare.aftercareapi.Services.notifications;

import com.aftercare.aftercareapi.Enums.NotificationChannel;
import com.aftercare.aftercareapi.config.NotificationProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.Set;

/**
 * Twilio programmable SMS over plain HTTPS (no SDK). Logs only the HTTP status and the Twilio error code, never the
 * request body or the credentials.
 */
@Component
@ConditionalOnProperty(name = "aftercare.notifications.sms.provider", havingValue = "twilio")
public class TwilioSmsSender implements NotificationSender {

    private static final Logger log = LoggerFactory.getLogger(TwilioSmsSender.class);
    private static final Set<Integer> PERMANENT_CODES = Set.of(21211, 21614, 21610, 21408);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final RestClient client;
    private final String accountSid;
    private final String fromNumber;

    public TwilioSmsSender(NotificationProperties props) {
        this(props, RestClient.builder().requestFactory(timeouts()));
    }

    /** For tests: a builder that may already be bound to a mock server. */
    TwilioSmsSender(NotificationProperties props, RestClient.Builder builder) {
        NotificationProperties.Twilio t = props.sms().twilio();
        this.accountSid = t.accountSid();
        this.fromNumber = t.fromNumber();
        this.client = builder
                .baseUrl(t.baseUrl())
                .defaultHeaders(h -> h.setBasicAuth(t.accountSid(), t.authToken()))
                .build();
    }

    private static SimpleClientHttpRequestFactory timeouts() {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(10_000);
        f.setReadTimeout(10_000);
        return f;
    }

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.SMS;
    }

    @Override
    public SendResult send(OutboundMessage m) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("To", m.to());
        form.add("From", fromNumber);
        form.add("Body", m.body());
        try {
            String body = client.post()
                    .uri("/2010-04-01/Accounts/{sid}/Messages.json", accountSid)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(String.class);
            String sid = null;
            if (body != null) {
                try {
                    JsonNode n = JSON.readTree(body);
                    sid = n.hasNonNull("sid") ? n.get("sid").asText() : null;
                } catch (Exception ignored) {
                    // a 2xx without a parsable body is still a success
                }
            }
            return new SendResult.Sent(sid == null ? "twilio" : (sid.length() > 64 ? sid.substring(0, 64) : sid));
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            Integer code = twilioCode(e.getResponseBodyAsString());
            log.warn("Twilio responded {} (error code {})", status, code);
            if (status == 400 && code != null && PERMANENT_CODES.contains(code)) {
                return new SendResult.Permanent("TWILIO_" + code);
            }
            if (status >= 400 && status < 500 && status != 429) {
                return new SendResult.Permanent("HTTP_" + status);
            }
            return new SendResult.Transient("HTTP_" + status);
        } catch (ResourceAccessException e) {
            log.warn("Twilio request failed ({})", e.getCause() == null ? "io" : e.getCause().getClass().getSimpleName());
            return new SendResult.Transient("IO");
        } catch (RuntimeException e) {
            return new SendResult.Transient("ERROR");
        }
    }

    private static Integer twilioCode(String body) {
        try {
            JsonNode n = JSON.readTree(body);
            return n.hasNonNull("code") ? n.get("code").asInt() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
