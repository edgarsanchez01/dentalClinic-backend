package com.dentalclinic.financial.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import com.dentalclinic.appointment.application.AppointmentCompletionParticipant;
import com.dentalclinic.appointment.application.AppointmentService;
import com.dentalclinic.appointment.domain.Appointment;
import com.dentalclinic.appointment.domain.AppointmentStatus;
import com.dentalclinic.financial.api.dto.CollectionQueueResponse;
import com.dentalclinic.financial.api.dto.CollectionQueueResponse.*;
import com.dentalclinic.financial.domain.FinancialContextUnavailableException;
import com.dentalclinic.identity.infrastructure.security.AuthenticatedUser;
import com.dentalclinic.scheduling.domain.exception.InvalidScheduleException;

/** A consistent, verified read model. Classification precedes logical pagination, never filters
 * an already-paginated HTTP result. Bounded candidate batches and page-sized response storage;
 * full verification is intentionally required for an honest summary (including corrupt records). */
@Service
public class CollectionQueueService {
    private final AppointmentService appointments;
    private final com.dentalclinic.financial.infrastructure.repository.ChargeRepository charges;
    private final FinancialContextAssembler assembler;
    private final FinancialContextTokenBuilder tokens;
    private final Clock clock;
    private final jakarta.persistence.EntityManager em;

    public CollectionQueueService(AppointmentService appointments,
            com.dentalclinic.financial.infrastructure.repository.ChargeRepository charges,
            FinancialContextAssembler assembler, FinancialContextTokenBuilder tokens,
            Clock clock, jakarta.persistence.EntityManager em) {
        this.appointments = appointments;
        this.charges = charges;
        this.assembler = assembler;
        this.tokens = tokens;
        this.clock = clock;
        this.em = em;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, propagation = Propagation.REQUIRES_NEW)
    public CollectionQueueResponse list(Long dentistId, Long patientId, Instant from, Instant to,
            State state, boolean includeSettled, int page, int size, AuthenticatedUser actor) {
        AppointmentCompletionParticipant.requireCollectionRole(actor);
        if (from != null && to != null && !from.isBefore(to)) {
            throw new InvalidScheduleException("El inicio del intervalo debe ser anterior al final.");
        }
        int safePage = Math.max(0, page), safeSize = Math.min(100, Math.max(1, size));
        long offset = (long) safePage * safeSize;
        var counts = new EnumMap<State, Long>(State.class);
        for (var value : State.values()) counts.put(value, 0L);
        long matched = 0, completed = 0;
        BigDecimal pending = BigDecimal.ZERO;
        List<Item> selected = new ArrayList<>();
        Instant observed = clock.instant();
        int batch = 0;
        boolean more;
        do {
            var candidates = appointments.list(dentistId, patientId, AppointmentStatus.COMPLETED,
                    from, to, actor, PageRequest.of(batch++, 100, Sort.by("startAt", "id")));
            more = candidates.hasNext();
            for (var appointment : candidates) {
                var financial = classify(appointment, actor);
                completed++;
                counts.merge(financial.state(), 1L, Long::sum);
                if (financial.pending() != null) pending = pending.add(financial.pending());
                boolean matches = state != null ? financial.state() == state
                        : includeSettled || financial.state() != State.SETTLED;
                if (matches) {
                    if (matched >= offset && selected.size() < safeSize) {
                        selected.add(new Item(appointments.view(appointment, actor), financial.state(),
                                financial.hasCharge(), financial.chargeId(), financial.total(), financial.applied(),
                                financial.pending(), financial.currency(), financial.token(), financial.reason()));
                    }
                    matched++;
                }
            }
            // This isolated read transaction owns its persistence context. Do not retain all scanned history.
            em.clear();
        } while (more);
        long pages = (matched + safeSize - 1) / safeSize;
        return new CollectionQueueResponse(List.copyOf(selected), safePage, safeSize, matched,
                (int) Math.min(Integer.MAX_VALUE, pages), observed,
                new Summary(java.util.Map.copyOf(counts), completed, pending, "MXN"));
    }

    private Classification classify(Appointment appointment, AuthenticatedUser actor) {
        List<AppointmentFinancialContextService.FinancialContextCharge> verified;
        String token;
        try {
            // Use the pure verifier here, not a transactional contextFor() proxy: a caught
            // integrity exception must classify REVIEW, not mark this read transaction rollback-only.
            verified = assembler.verifyAll(appointment.getId(), appointment.getPatientId(),
                    charges.findByAppointmentIdOrderByIdAsc(appointment.getId()), actor);
            token = tokens.tokenFor(appointment, verified);
        }
        catch (FinancialContextUnavailableException inconsistent) {
            return new Classification(State.REVIEW_REQUIRED, true, null, null, null, null, null,
                    null, "FINANCIAL_CONTEXT_UNAVAILABLE");
        }
        var active = verified.stream().filter(c -> !c.charge().isCancelled()).toList();
        if (active.size() > 1) {
            return new Classification(State.REVIEW_REQUIRED, true, null, null, null, null, null,
                    token, "MULTIPLE_ACTIVE_CHARGES");
        }
        if (active.isEmpty()) {
            // Even with an unusable economic reference, absence of a charge never becomes debt.
            return new Classification(State.NO_CHARGE, !verified.isEmpty(), null, null, null,
                    null, null, token, usable(appointment) ? null : "ECONOMIC_REFERENCE_REQUIRED");
        }
        var charge = active.get(0);
        State state = switch (charge.state().status()) {
            case PENDIENTE -> State.PENDING;
            case PARCIAL -> State.PARTIAL;
            case PAGADO -> State.SETTLED;
            case ANULADO -> throw new IllegalStateException("Cargo activo anulado.");
        };
        return new Classification(state, true, charge.charge().getId(), charge.charge().getTotalAmount(),
                charge.state().appliedAmount(), charge.state().pendingBalance(), charge.charge().getCurrency(),
                token, null);
    }

    private static boolean usable(Appointment appointment) {
        BigDecimal price = appointment.getServiceReferencePrice();
        return appointment.getServiceId() != null && appointment.getServiceName() != null
                && !appointment.getServiceName().isBlank() && appointment.getServiceDurationMinutes() != null
                && appointment.getServiceDurationMinutes() > 0 && price != null && price.signum() > 0
                && price.stripTrailingZeros().scale() <= 2
                && price.stripTrailingZeros().precision() - price.stripTrailingZeros().scale() <= 15;
    }

    private record Classification(State state, boolean hasCharge, Long chargeId, BigDecimal total,
            BigDecimal applied, BigDecimal pending, String currency, String token, String reason) { }
}
