package com.tickethub.notification.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tickethub.notification.event.BookingConfirmedEvent;
import com.tickethub.notification.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Kafka -> Notification. A message that cannot be parsed is logged and skipped (never blocks the topic). */
@Component
public class BookingConfirmedListener {

    private static final Logger log = LoggerFactory.getLogger(BookingConfirmedListener.class);

    private final ObjectMapper objectMapper;
    private final NotificationService notificationService;

    public BookingConfirmedListener(ObjectMapper objectMapper, NotificationService notificationService) {
        this.objectMapper = objectMapper;
        this.notificationService = notificationService;
    }

    @KafkaListener(topics = "${notification.topic}")
    public void onMessage(String json) {
        BookingConfirmedEvent event;
        try {
            event = objectMapper.readValue(json, BookingConfirmedEvent.class);
        } catch (Exception e) {
            log.error("EVENT_UNREADABLE skipping message: {}", e.toString());
            return;
        }
        notificationService.process(event);
    }
}
