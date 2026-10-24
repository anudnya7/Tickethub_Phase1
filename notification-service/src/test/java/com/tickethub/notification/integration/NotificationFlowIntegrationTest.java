package com.tickethub.notification.integration;

import com.tickethub.notification.domain.NotificationStatus;
import com.tickethub.notification.event.BookingConfirmedEvent;
import com.tickethub.notification.dto.NotificationResponse;
import com.tickethub.notification.listener.BookingConfirmedListener;
import com.tickethub.notification.repository.NotificationRepository;
import com.tickethub.notification.sender.EmailSender;
import com.tickethub.notification.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class NotificationFlowIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired NotificationService service;
    @Autowired BookingConfirmedListener listener;
    @Autowired NotificationRepository repository;
    @Autowired MockMvc mockMvc;
    @MockitoBean EmailSender sender;

    @BeforeEach
    void clean() {
        repository.deleteAll();
        reset(sender);
    }

    private BookingConfirmedEvent event(long bookingId) {
        return new BookingConfirmedEvent("booking-confirmed-" + bookingId, bookingId, "cust-1", "a@b.dev", 10L,
                List.of(1L, 2L), new BigDecimal("500.00"), "INR", Instant.now());
    }

    @Test
    void eventSendsOneEmailAndIsRecordedAsSent() throws Exception {
        service.process(event(1));

        verify(sender, times(1)).send(eq("a@b.dev"), any(), any());
        assertEquals(NotificationStatus.SENT, repository.findByBookingIdOrderByIdAsc(1L).get(0).getStatus());
        mockMvc.perform(get("/internal/notifications").param("bookingId", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].status").value("SENT"));
    }

    @Test
    void theSameEventDeliveredTwiceSendsOnlyOneEmail() {
        service.process(event(2));
        service.process(event(2));

        verify(sender, times(1)).send(any(), any(), any());
        assertEquals(1, repository.count());
    }

    @Test
    void aFailingEmailIsRecordedAndNeverThrows() {
        doThrow(new RuntimeException("smtp down")).when(sender).send(any(), any(), any());

        service.process(event(3));       // must not throw: the booking is already confirmed

        NotificationResponse n = service.findByBooking(3L).get(0);
        assertEquals(NotificationStatus.FAILED, n.status());
    }

    @Test
    void garbageMessagesAreSkippedWithoutError() {
        listener.onMessage("this is not json");
        listener.onMessage("{\"eventId\":\"x\"}");

        verify(sender, never()).send(any(), any(), any());
        assertEquals(0, repository.count());
    }

    @Test
    void listenerParsesTheBookingContract() {
        listener.onMessage("{\"eventId\":\"booking-confirmed-4\",\"bookingId\":4,\"customerId\":\"c\","
                + "\"recipient\":\"x@y.dev\",\"showId\":1,\"seatIds\":[1],\"amount\":250.00,\"currency\":\"INR\","
                + "\"confirmedAt\":\"2030-01-01T10:00:00Z\",\"someFutureField\":true}");

        verify(sender).send(eq("x@y.dev"), any(), any());
    }
}
