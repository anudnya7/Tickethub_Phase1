package com.tickethub.payment.scheduler;

import com.tickethub.payment.service.PaymentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Self-healing: if Booking was down when a payment finished, keep retrying until it has the result. */
@Component
@ConditionalOnProperty(name = "payment.sync.enabled", havingValue = "true", matchIfMissing = true)
public class PaymentSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(PaymentSyncScheduler.class);
    private final PaymentService paymentService;

    public PaymentSyncScheduler(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @Scheduled(fixedDelayString = "${payment.sync.scan-interval-ms:15000}", initialDelayString = "10000")
    public void retry() {
        int done = paymentService.retryUnsyncedPayments();
        if (done > 0) {
            log.info("Synced {} payment result(s) to Booking", done);
        }
    }
}
