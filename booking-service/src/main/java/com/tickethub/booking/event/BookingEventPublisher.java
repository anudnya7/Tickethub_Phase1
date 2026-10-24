package com.tickethub.booking.event;

/** Port for announcing booking facts. Kafka today; the service layer does not know or care. */
public interface BookingEventPublisher {

    /** Must never throw: a failed announcement must not undo a confirmed booking (principle 5). */
    void publishBookingConfirmed(BookingConfirmedEvent event);
}
