package com.aftercare.aftercareapi.Services.notifications;

import com.aftercare.aftercareapi.Enums.NotificationTemplate;
import com.aftercare.aftercareapi.Services.AccessLinkService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Event-driven automated sends: the link message after a follow-up is enrolled or an appointment is booked. They run after
 * the surrounding transaction commits and never fail the caller.
 */
@Component
public class NotificationTriggers {

    private static final Logger log = LoggerFactory.getLogger(NotificationTriggers.class);

    private final NotificationService notifications;
    private final AccessLinkService links;

    public NotificationTriggers(NotificationService notifications, AccessLinkService links) {
        this.notifications = notifications;
        this.links = links;
    }

    public void followUpEnrolled(long patientId, long followUpId) {
        afterCommit(() -> links.currentLinkId(true, followUpId).ifPresent(linkId ->
                notifications.enqueueAutomated(NotificationTemplate.FOLLOW_UP_LINK, patientId, followUpId, null,
                        "FOLLOW_UP_LINK:" + followUpId + ":" + linkId)));
    }

    public void appointmentCreated(long patientId, long appointmentId) {
        afterCommit(() -> links.currentLinkId(false, appointmentId).ifPresent(linkId ->
                notifications.enqueueAutomated(NotificationTemplate.APPOINTMENT_LINK, patientId, null, appointmentId,
                        "APPOINTMENT_LINK:" + appointmentId + ":" + linkId)));
    }

    private static void afterCommit(Runnable action) {
        Runnable safe = () -> {
            try {
                action.run();
            } catch (RuntimeException e) {
                log.debug("Notification trigger skipped ({})", e.getClass().getSimpleName());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    safe.run();
                }
            });
        } else {
            safe.run();
        }
    }
}
