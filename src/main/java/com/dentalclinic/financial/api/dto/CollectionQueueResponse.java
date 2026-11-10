package com.dentalclinic.financial.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import com.dentalclinic.appointment.api.dto.AppointmentResponse;

public record CollectionQueueResponse(List<Item> content, int page, int size, long totalElements,
        int totalPages, Instant observedAt, Summary summary) {
    /** Derived only. Never persisted as an appointment or charge state. */
    public enum State { NO_CHARGE, PENDING, PARTIAL, SETTLED, REVIEW_REQUIRED }
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
    public record Item(AppointmentResponse appointment, State collectionState, boolean hasRecordedCharge,
            Long activeChargeId, BigDecimal totalAmount, BigDecimal appliedAmount,
            BigDecimal pendingBalance, String currency, String financialContextToken, String reviewReason) { }
    /** Counts cover all completed appointments matching date/patient/dentist filters, before state filtering.
     * Money covers verified unambiguous active charges only; not a valuation of unrecorded care. */
    public record Summary(Map<State, Long> counts, long completedCount, BigDecimal verifiedPendingBalance,
            String currency) { }
}
