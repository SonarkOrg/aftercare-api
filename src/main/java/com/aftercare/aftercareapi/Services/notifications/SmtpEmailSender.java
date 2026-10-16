package com.aftercare.aftercareapi.Services.notifications;

import com.aftercare.aftercareapi.Enums.NotificationChannel;
import com.aftercare.aftercareapi.config.NotificationProperties;
import jakarta.mail.Address;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/** SMTP email through Spring Mail (provider smtp). Plain text, UTF-8, marked auto-submitted. */
@Component
@ConditionalOnProperty(name = "aftercare.notifications.email.provider", havingValue = "smtp")
public class SmtpEmailSender implements NotificationSender {

    private static final Logger log = LoggerFactory.getLogger(SmtpEmailSender.class);

    private final JavaMailSender mailSender;
    private final String from;

    public SmtpEmailSender(JavaMailSender mailSender, NotificationProperties props) {
        this.mailSender = mailSender;
        this.from = props.email().from();
    }

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.EMAIL;
    }

    @Override
    public SendResult send(OutboundMessage m) {
        try {
            MimeMessage msg = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(msg, false, "UTF-8");
            helper.setFrom(from);
            helper.setTo(m.to());
            helper.setSubject(m.subject() == null ? "" : m.subject());
            helper.setText(m.body(), false);
            msg.addHeader("Auto-Submitted", "auto-generated");
            mailSender.send(msg);
            String id = null;
            try {
                id = msg.getMessageID();
            } catch (MessagingException ignored) {
                // no id available
            }
            return new SendResult.Sent(id == null ? "smtp" : (id.length() > 64 ? id.substring(0, 64) : id));
        } catch (AddressException | org.springframework.mail.MailParseException e) {
            return new SendResult.Permanent("BAD_ADDRESS");
        } catch (MailAuthenticationException e) {
            log.warn("SMTP authentication failed");
            return new SendResult.Transient("AUTH");
        } catch (MailSendException e) {
            return allAddressesInvalid(e) ? new SendResult.Permanent("REJECTED") : new SendResult.Transient("MAIL_SEND");
        } catch (MailException e) {
            return new SendResult.Transient("MAIL_ERROR");
        } catch (MessagingException e) {
            return new SendResult.Transient("MESSAGING");
        } catch (RuntimeException e) {
            return new SendResult.Transient("ERROR");
        }
    }

    /** True when the server refused every recipient as invalid (a retry can never help). */
    static boolean allAddressesInvalid(MailSendException e) {
        boolean sawInvalid = false;
        for (Exception inner : e.getMessageExceptions()) {
            if (inner instanceof SendFailedException sfe) {
                Address[] invalid = sfe.getInvalidAddresses();
                Address[] validUnsent = sfe.getValidUnsentAddresses();
                if (invalid != null && invalid.length > 0 && (validUnsent == null || validUnsent.length == 0)) {
                    sawInvalid = true;
                    continue;
                }
            }
            return false;
        }
        if (!sawInvalid && e.getCause() instanceof SendFailedException sfe) {
            Address[] invalid = sfe.getInvalidAddresses();
            Address[] validUnsent = sfe.getValidUnsentAddresses();
            return invalid != null && invalid.length > 0 && (validUnsent == null || validUnsent.length == 0);
        }
        return sawInvalid;
    }
}
