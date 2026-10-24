package com.tickethub.notification.service;

import com.tickethub.notification.domain.Notification;
import com.tickethub.notification.dto.NotificationResponse;
import com.tickethub.notification.metrics.NotificationMetrics;
import com.tickethub.notification.event.BookingConfirmedEvent;
import com.tickethub.notification.repository.NotificationRepository;
import com.tickethub.notification.sender.EmailSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;

/**
 * Turns a BookingConfirmed event into ONE email (ST-20/ST-37). Rules:
 *  - the same event id is processed once (unique constraint), even if Kafka delivers it twice;
 *  - a failed email is recorded as FAILED and never throws: the booking is already CONFIRMED and stays so.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository repository;
    private final EmailSender sender;
    private final Clock clock;
    private final NotificationMetrics metrics;

    public NotificationService(NotificationRepository repository, EmailSender sender, Clock clock, NotificationMetrics metrics) {
        this.repository = repository;
        this.sender = sender;
        this.clock = clock;
        this.metrics = metrics;
    }

    public void process(BookingConfirmedEvent event) {
        if (event == null || event.eventId() == null || event.bookingId() == null || event.recipient() == null) {
            log.warn("NOTIFICATION_SKIPPED invalid event {}", event);
            return;
        }
        MDC.put("bookingId", String.valueOf(event.bookingId()));
        try {
            if (repository.existsByEventId(event.eventId())) {
                metrics.notification("DUPLICATE");
                log.info("NOTIFICATION_DUPLICATE eventId={} ignored", event.eventId());
                return;
            }
            Notification n = new Notification(event.eventId(), event.bookingId(), event.recipient(),
                    "Your TicketHub booking #" + event.bookingId() + " is confirmed", body(event), clock.instant());
            try {
                n = repository.saveAndFlush(n);
            } catch (DataIntegrityViolationException raceWithAnotherConsumer) {
                log.info("NOTIFICATION_DUPLICATE eventId={} (race) ignored", event.eventId());
                return;
            }
            try {
                sender.send(n.getRecipient(), n.getSubject(), n.getBody());
                n.markSent(clock.instant());
                metrics.notification("SENT");
                log.info("NOTIFICATION_SENT eventId={} bookingId={}", event.eventId(), event.bookingId());
            } catch (RuntimeException e) {
                n.markFailed(e.toString(), clock.instant());
                metrics.notification("FAILED");
                log.error("NOTIFICATION_FAILED eventId={} bookingId={}: {}", event.eventId(), event.bookingId(), e.toString());
            }
            repository.save(n);
        } finally {
            MDC.remove("bookingId");
        }
    }

    public List<NotificationResponse> findByBooking(Long bookingId) {
        return repository.findByBookingIdOrderByIdAsc(bookingId).stream().map(NotificationResponse::from).toList();
    }

    private String body(BookingConfirmedEvent e) {
        return "Hi,\n\nYour booking #" + e.bookingId() + " is confirmed.\n"
                + "Show: " + e.showId() + "\n"
                + "Seats: " + e.seatIds() + "\n"
                + "Paid: " + e.amount() + " " + e.currency() + "\n\nThank you for using TicketHub.";
    }
}
