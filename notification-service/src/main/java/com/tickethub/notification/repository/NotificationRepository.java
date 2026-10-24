package com.tickethub.notification.repository;

import com.tickethub.notification.domain.Notification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NotificationRepository extends JpaRepository<Notification, Long> {
    boolean existsByEventId(String eventId);

    List<Notification> findByBookingIdOrderByIdAsc(Long bookingId);
}
