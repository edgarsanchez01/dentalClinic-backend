package com.dentalclinic.financial.application;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import com.dentalclinic.appointment.application.AppointmentCompletionParticipant;
import com.dentalclinic.appointment.application.OperationIdempotencyStore;
import com.dentalclinic.appointment.domain.AppointmentStatus;
import com.dentalclinic.financial.api.dto.AppointmentCollectionResponse;
import com.dentalclinic.financial.domain.FinancialConflictException;
import com.dentalclinic.identity.infrastructure.security.AuthenticatedUser;

/** One commit: charge/items, optional payment and durable receipt. Never writes clinical state. */
@Service
@Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
class AppointmentCollectionService {
    private static final String OPERATION = "APPOINTMENT_COLLECTION";
    private final AppointmentCompletionParticipant appointments;
    private final ContextualCheckoutFinancialParticipant financial;
    private final OperationIdempotencyStore idempotency;
    private final Clock clock;

    AppointmentCollectionService(AppointmentCompletionParticipant appointments,
            ContextualCheckoutFinancialParticipant financial, OperationIdempotencyStore idempotency, Clock clock) {
        this.appointments = appointments;
        this.financial = financial;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    public AppointmentCollectionResponse collect(CollectionCommand command, AuthenticatedUser actor) {
        AppointmentCompletionParticipant.requireCollectionRole(actor);
        java.util.Objects.requireNonNull(command, "command es obligatorio");
        String hash = idempotency.lock(actor, OPERATION, command.idempotencyKey(), command);
        var appointment = appointments.lockForCollection(command.appointmentId(), actor);
        var replay = idempotency.replay(actor, OPERATION, command.idempotencyKey(), hash,
                AppointmentCollectionResponse.class);
        if (replay != null) {
            var execution = replay.financialAtCollection();
            if (!appointment.id().equals(replay.appointmentId())
                    || !appointment.patientId().equals(execution.patientId())) {
                throw new IllegalStateException("Referencias del recibo incompatibles.");
            }
            financial.verifyReplayReferences(actor, appointment.id(), appointment.patientId(),
                    execution.chargeId(), execution.paymentId());
            return replay;
        }
        if (appointment.status() != AppointmentStatus.COMPLETED) {
            throw new FinancialConflictException("Solo se cobra una atencion COMPLETED.");
        }
        if (appointment.occurrenceVersion() != command.expectedOccurrenceVersion()) {
            throw new FinancialConflictException("La ocurrencia cambio; vuelve a leer la cita.");
        }
        var context = financial.lockAndVerifyCollection(appointment.id(), actor);
        var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        var execution = financial.executeCollection(context, command.charge(), command.payment(), actor, now);
        var response = new AppointmentCollectionResponse(appointment.id(), appointment.status(),
                appointment.occurrenceVersion(), now, execution);
        idempotency.remember(actor, OPERATION, command.idempotencyKey(), hash, response);
        return response;
    }
}
