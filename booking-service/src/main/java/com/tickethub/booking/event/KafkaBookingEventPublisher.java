package com.tickethub.booking.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes BookingConfirmed to Kafka, keyed by bookingId (same booking -> same partition -> ordered).
 * Failures are LOGGED and visible, never thrown. Guaranteed delivery (outbox) is Phase 2 (ST-36).
 */
@Component
public class KafkaBookingEventPublisher implements BookingEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaBookingEventPublisher.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final String topic;

    public KafkaBookingEventPublisher(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper,
                                      @Value("${booking.events.topic}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.topic = topic;
    }

    @Override
    public void publishBookingConfirmed(BookingConfirmedEvent event) {
        try {
            String json = objectMapper.writeValueAsString(event);
            kafkaTemplate.send(topic, String.valueOf(event.bookingId()), json)
                    .whenComplete((result, error) -> {
                        if (error != null) {
                            log.error("EVENT_PUBLISH_FAILED topic={} eventId={} bookingId={}: {}",
                                    topic, event.eventId(), event.bookingId(), error.toString());
                        } else {
                            log.info("Published {} to {}-{}@{}", event.eventId(), topic,
                                    result.getRecordMetadata().partition(), result.getRecordMetadata().offset());
                        }
                    });
        } catch (Exception e) {
            log.error("EVENT_PUBLISH_FAILED topic={} eventId={} bookingId={}: {}",
                    topic, event.eventId(), event.bookingId(), e.toString());
        }
    }
}
