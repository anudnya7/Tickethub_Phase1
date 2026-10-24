package com.tickethub.booking.controller;

import com.tickethub.booking.dto.BookingResponse;
import com.tickethub.booking.dto.PaymentResultRequest;
import com.tickethub.booking.dto.SeatStateResponse;
import com.tickethub.booking.service.BookingService;
import com.tickethub.booking.service.SeatReservationService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Service-to-service API. NOT routed by the API Gateway, so customers cannot reach it.
 * (Still unauthenticated between services until sprint S7.)
 */
@RestController
@RequestMapping("/internal")
public class InternalBookingController {

    private final BookingService bookingService;
    private final SeatReservationService seatReservationService;

    public InternalBookingController(BookingService bookingService, SeatReservationService seatReservationService) {
        this.bookingService = bookingService;
        this.seatReservationService = seatReservationService;
    }

    /** Event/Show asks which seats of a show are RESERVED or BOOKED (ST-05). */
    @GetMapping("/shows/{showId}/seat-states")
    public List<SeatStateResponse> seatStates(@PathVariable Long showId) {
        return seatReservationService.getSeatStates(showId);
    }

    /** Payment will read the amount and status of a booking (sprint S5). */
    @GetMapping("/bookings/{id}")
    public BookingResponse booking(@PathVariable Long id) {
        return bookingService.getBookingForService(id);
    }

    /**
     * Payment will report SUCCESS/FAILED here (sprint S5). It already exists because ST-09 requires that an
     * expired booking can never be confirmed; Booking decides, Payment only reports.
     */
    @PostMapping("/bookings/{id}/payment-result")
    public BookingResponse paymentResult(@PathVariable Long id, @Valid @RequestBody PaymentResultRequest request) {
        return bookingService.applyPaymentResult(id, request.result());
    }
}
