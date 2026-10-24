package com.tickethub.booking.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicConfig {

    /** Created on startup if missing. 3 partitions so several consumers of one group can share the work (ST-60). */
    @Bean
    public NewTopic bookingConfirmedTopic(@Value("${booking.events.topic}") String topic,
                                          @Value("${booking.events.partitions}") int partitions) {
        return TopicBuilder.name(topic).partitions(partitions).replicas(1).build();
    }
}
