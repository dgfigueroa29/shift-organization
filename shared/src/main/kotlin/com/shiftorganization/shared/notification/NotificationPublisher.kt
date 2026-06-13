package com.shiftorganization.shared.notification

import com.shiftorganization.shared.domain.Booking
import com.shiftorganization.shared.domain.RecurringEvent

/**
 * Abstraction over the SNS topic used to fan out domain events.
 *
 * Concrete implementations wrap the AWS SDK's `SnsClient` and serialise the
 * relevant entity into a [com.shiftorganization.shared.model.NotificationPayload]
 * before publishing.
 *
 * Service code calls these methods *outside* the transactional boundary, so
 * implementations should be best-effort — they may swallow transport errors as
 * long as they surface them through logging. The caller treats a publishing
 * failure as a degraded notification, not a failure of the originating action.
 */
interface NotificationPublisher {

    /** Publish a `BOOKING_CREATED` event for the given confirmed [booking]. */
    fun publishBookingCreated(booking: Booking)

    /** Publish a `BOOKING_CANCELLED` event for the given cancelled [booking]. */
    fun publishBookingCancelled(booking: Booking)

    /** Publish a `RECURRING_EVENT_TRIGGERED` event for the given [event]. */
    fun publishRecurringEventTriggered(event: RecurringEvent)
}
