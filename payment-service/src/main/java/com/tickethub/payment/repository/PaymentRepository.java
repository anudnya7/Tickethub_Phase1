package com.tickethub.payment.repository;

import com.tickethub.payment.domain.Payment;
import com.tickethub.payment.domain.PaymentStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {
    Optional<Payment> findByBookingId(Long bookingId);

    Optional<Payment> findByProviderReference(String providerReference);

    List<Payment> findByBookingSyncedFalseAndStatusIn(List<PaymentStatus> statuses, Pageable pageable);
}
