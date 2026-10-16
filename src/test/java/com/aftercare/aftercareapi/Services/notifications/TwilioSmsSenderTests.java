package com.aftercare.aftercareapi.Services.notifications;

import com.aftercare.aftercareapi.Enums.NotificationChannel;
import com.aftercare.aftercareapi.config.NotificationProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class TwilioSmsSenderTests {

    private static final String URL = "https://api.twilio.com/2010-04-01/Accounts/ACtest/Messages.json";
    private MockRestServiceServer server;
    private TwilioSmsSender sender;
    private final OutboundMessage message = new OutboundMessage(NotificationChannel.SMS, "+15145550100", null, "Hello é 1", 7L, "FOLLOW_UP_LINK", "•••-•••-0100");

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        NotificationProperties props = new NotificationProperties(
                new NotificationProperties.Email("log", ""),
                new NotificationProperties.Sms("twilio", new NotificationProperties.Twilio("ACtest", "s3cret", "+15145559999", "https://api.twilio.com")),
                Duration.ofSeconds(30), 50, 300, 4, 3, new NotificationProperties.QuietHours("21:00", "08:00"));
        sender = new TwilioSmsSender(props, builder);
    }

    @Test
    void postsTheFormWithBasicAuthAndReturnsTheSid() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("To", "+15145550100");
        form.add("From", "+15145559999");
        form.add("Body", "Hello é 1");
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Basic " + Base64.getEncoder().encodeToString("ACtest:s3cret".getBytes(StandardCharsets.UTF_8))))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().formData(form))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body("{\"sid\":\"SM123\",\"status\":\"queued\"}"));
        assertEquals(new SendResult.Sent("SM123"), sender.send(message));
        server.verify();
    }

    @Test
    void rejectedNumbersArePermanent() {
        for (int code : new int[]{21211, 21614, 21610, 21408}) {
            server.reset();
            server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                    .body("{\"code\":" + code + ",\"message\":\"bad\",\"status\":400}"));
            assertEquals(new SendResult.Permanent("TWILIO_" + code), sender.send(message));
        }
    }

    @Test
    void otherClientErrorsArePermanentExceptTooManyRequests() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON).body("{\"code\":99999}"));
        assertEquals(new SendResult.Permanent("HTTP_400"), sender.send(message));
        server.reset();
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        assertEquals(new SendResult.Permanent("HTTP_401"), sender.send(message));
        server.reset();
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        assertEquals(new SendResult.Transient("HTTP_429"), sender.send(message));
    }

    @Test
    void serverErrorsAndTimeoutsAreTransient() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        assertEquals(new SendResult.Transient("HTTP_500"), sender.send(message));
        server.reset();
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        assertInstanceOf(SendResult.Transient.class, sender.send(message));
        server.reset();
        server.expect(requestTo(URL)).andRespond(request -> {
            throw new SocketTimeoutException("read timed out");
        });
        assertEquals(new SendResult.Transient("IO"), sender.send(message));
    }

    @Test
    void aSuccessWithoutAParsableBodyIsStillSent() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.CREATED).body("not json"));
        assertInstanceOf(SendResult.Sent.class, sender.send(message));
    }

    @Test
    void theMessageNeverPrintsItsRecipientOrBody() {
        assertFalse(message.toString().contains("+1514"));
        assertFalse(message.toString().contains("Hello"));
        assertEquals("OutboundMessage[SMS]", message.toString());
    }
}
