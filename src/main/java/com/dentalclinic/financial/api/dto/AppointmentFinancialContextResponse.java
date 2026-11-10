package com.dentalclinic.financial.api.dto;

import java.time.Instant;
import java.util.List;

import com.dentalclinic.appointment.api.dto.AppointmentServiceSnapshot;
import com.dentalclinic.appointment.domain.Appointment;
import com.dentalclinic.financial.application.AppointmentFinancialContextService.FinancialContext;
import com.dentalclinic.financial.domain.ChargeAssociationState;

/**
 * Contexto financiero de una atencion: <strong>solo lectura</strong> (UX-05B.1, Etapa 2).
 *
 * <p>Describe el conjunto de cargos que recaen sobre la cita y lo devuelve <strong>completo</strong>
 * o no lo devuelve: si el conjunto no puede verificarse integramente para el actor, la respuesta
 * es {@code 409 FINANCIAL_CONTEXT_UNAVAILABLE} en lugar de un subconjunto.
 *
 * <p><strong>Sin importes agregados.</strong> El nivel superior no lleva total, monto aplicado ni
 * saldo: esos datos son de cada cargo. Sin cargos asociados la vista esta <strong>vacia</strong>,
 * no en cero (C-04): {@code charges} vacio y {@code chargeCount} en 0 significan «no hay cargo»,
 * no «debe 0».
 *
 * <p><strong>Ningun estado selecciona un cargo</strong>: {@link ChargeAssociationState} solo
 * clasifica la cardinalidad (D-01 sigue abierta). No existe ningun campo «cargo elegido».
 *
 * <p>{@code observedAt} es el instante de la lectura (reloj del servidor) y
 * {@code appointmentOccurrenceVersion} la version de ocurrencia de la cita. Ambos permiten
 * correlacionar la vista con la cita observada; <strong>no</strong> autorizan ningun cobro ni
 * seleccionan un cargo.
 */
public record AppointmentFinancialContextResponse(Long appointmentId, Long patientId, Long dentistId,
        int appointmentOccurrenceVersion, Instant observedAt, String financialContextToken,
        ChargeAssociationState associationState, int chargeCount, AppointmentServiceSnapshot service,
        List<FinancialContextChargeResponse> charges) {

    public static AppointmentFinancialContextResponse from(FinancialContext context) {
        var appointment = context.appointment();
        return new AppointmentFinancialContextResponse(appointment.getId(), appointment.getPatientId(),
                appointment.getDentistId(), appointment.getOccurrenceVersion(), context.observedAt(),
                context.financialContextToken(), context.associationState(), context.charges().size(),
                serviceSnapshotOf(appointment),
                context.charges().stream().map(FinancialContextChargeResponse::from).toList());
    }

    /**
     * Snapshot historico del servicio agendado en la cita. Es <strong>opcional</strong>: una cita
     * sin servicio capturado (p. ej. creada sin catalogo) devuelve {@code null}, nunca un valor
     * inventado.
     */
    private static AppointmentServiceSnapshot serviceSnapshotOf(Appointment appointment) {
        if (appointment.getServiceId() == null || appointment.getServiceName() == null
                || appointment.getServiceReferencePrice() == null
                || appointment.getServiceDurationMinutes() == null) {
            return null;
        }
        return new AppointmentServiceSnapshot(appointment.getServiceId(), appointment.getServiceName(),
                appointment.getServiceDescription(), appointment.getServiceReferencePrice(),
                appointment.getServiceDurationMinutes());
    }
}
