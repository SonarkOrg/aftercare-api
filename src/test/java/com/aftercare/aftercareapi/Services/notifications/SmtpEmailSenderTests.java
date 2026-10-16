package com.aftercare.aftercareapi.Services.notifications;

import com.aftercare.aftercareapi.Enums.NotificationChannel;
import com.aftercare.aftercareapi.config.NotificationProperties;
import jakarta.mail.Address;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SmtpEmailSenderTests {

    private JavaMailSender mail;
    private SmtpEmailSender sender;
    private final OutboundMessage message = new OutboundMessage(NotificationChannel.EMAIL, "marie@example.com",
            "Clinique - Rappel : suivi du jour", "Bonjour é à ç\n\nlien", 3L, "CHECKIN_REMINDER", "m•••@example.com");

    @BeforeEach
    void setUp() {
        mail = mock(JavaMailSender.class);
        when(mail.createMimeMessage()).thenAnswer(i -> new JavaMailSenderImpl().createMimeMessage());
        NotificationProperties props = new NotificationProperties(
                new NotificationProperties.Email("smtp", "Clinique Saint-Laurent <no-reply@clinique.example>"),
                new NotificationProperties.Sms("log", new NotificationProperties.Twilio("", "", "", "https://api.twilio.com")),
                Duration.ofSeconds(30), 50, 300, 4, 3, new NotificationProperties.QuietHours("21:00", "08:00"));
        sender = new SmtpEmailSender(mail, props);
    }

    @Test
    void sendsAPlainUtf8MessageMarkedAutoSubmitted() throws Exception {
        SendResult r = sender.send(message);
        assertInstanceOf(SendResult.Sent.class, r);
        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mail).send(captor.capture());
        MimeMessage m = captor.getValue();
        assertEquals("Clinique - Rappel : suivi du jour", m.getSubject());
        assertEquals("Clinique Saint-Laurent <no-reply@clinique.example>", m.getFrom()[0].toString());
        assertEquals("marie@example.com", ((InternetAddress) m.getAllRecipients()[0]).getAddress());
        assertEquals("auto-generated", m.getHeader("Auto-Submitted")[0]);
        m.saveChanges();
        assertTrue(m.getContentType().toLowerCase().contains("text/plain"), m.getContentType());
        assertTrue(m.getContentType().toLowerCase().contains("utf-8"), m.getContentType());
        assertEquals("Bonjour é à ç\n\nlien", m.getContent().toString());
    }

    @Test
    void authenticationFailuresAreTransient() {
        doThrow(new MailAuthenticationException("bad credentials")).when(mail).send(any(MimeMessage.class));
        assertEquals(new SendResult.Transient("AUTH"), sender.send(message));
    }

    @Test
    void aServerThatRejectsAllAddressesIsPermanent() throws Exception {
        MimeMessage dummy = new JavaMailSenderImpl().createMimeMessage();
        SendFailedException failed = new SendFailedException("invalid", null, new Address[0], new Address[0],
                new Address[]{new InternetAddress("marie@example.com")});
        doThrow(new MailSendException("failed", null, Map.of(dummy, failed))).when(mail).send(any(MimeMessage.class));
        assertEquals(new SendResult.Permanent("REJECTED"), sender.send(message));
    }

    @Test
    void otherMailProblemsAreTransient() throws Exception {
        MimeMessage dummy = new JavaMailSenderImpl().createMimeMessage();
        // a send failure that is not about invalid addresses (for example a dropped connection)
        doThrow(new MailSendException("io", null, Map.of(dummy, new java.io.IOException("connection reset"))))
                .when(mail).send(any(MimeMessage.class));
        assertInstanceOf(SendResult.Transient.class, sender.send(message));
        doThrow(new MailPreparationException("prep")).when(mail).send(any(MimeMessage.class));
        assertInstanceOf(SendResult.Transient.class, sender.send(message));
        doThrow(new IllegalStateException("boom")).when(mail).send(any(MimeMessage.class));
        assertInstanceOf(SendResult.Transient.class, sender.send(message));
    }

    @Test
    void anUnparsableAddressIsPermanent() {
        OutboundMessage bad = new OutboundMessage(NotificationChannel.EMAIL, "not an address", "s", "b");
        assertInstanceOf(SendResult.Permanent.class, sender.send(bad));
        verify(mail, never()).send(any(MimeMessage.class));
    }
}
