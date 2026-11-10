package com.dentalclinic.financial.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.dentalclinic.appointment.application.AppointmentService;
import com.dentalclinic.appointment.domain.Appointment;
import com.dentalclinic.financial.domain.Charge;
import com.dentalclinic.financial.domain.ChargeAssociationState;
import com.dentalclinic.financial.domain.ChargeFinancialState;
import com.dentalclinic.financial.domain.ChargeItem;
import com.dentalclinic.financial.domain.ChargeStatus;
import com.dentalclinic.financial.domain.FinancialContextUnavailableException;
import com.dentalclinic.financial.domain.FinancialMovement;
import com.dentalclinic.financial.infrastructure.repository.ChargeRepository;
import com.dentalclinic.identity.domain.Role;
import com.dentalclinic.identity.domain.exception.ForbiddenOperationException;
import com.dentalclinic.identity.domain.exception.NotFoundException;
import com.dentalclinic.identity.infrastructure.security.AuthenticatedUser;

/**
 * Contexto financiero de una atencion: <strong>solo lectura</strong> (UX-05B.1, Etapa 2).
 *
 * <p>Devuelve el conjunto <strong>completo</strong> de cargos asociados a una cita con sus
 * conceptos, movimientos, total, monto aplicado y saldo pendiente, o un {@code 409} generico si el
 * conjunto no puede verificarse integramente. Es exactamente el permiso de lectura que la Etapa 1
 * dejo pendiente; <strong>no</strong> cobra, no crea cargos, no registra pagos y no cierra nada.
 *
 * <p><strong>Reglas de la Etapa 2 (aprobadas):</strong>
 *
 * <ul>
 *   <li>La cita se autoriza con {@link AppointmentService#get(Long, AuthenticatedUser)}, <strong>no</strong>
 *       con {@code resolveReference}: aqui manda el alcance de agenda (el {@code DENTIST} solo
 *       consulta sus propias citas; una cita ajena responde 404).</li>
 *   <li>La autorizacion se <strong>repite aqui</strong>, no solo en la matriz HTTP: una invocacion
 *       directa del servicio tampoco concede acceso al {@code PATIENT} (403).</li>
 *   <li>Se consultan <strong>todos</strong> los cargos de la cita, sin paginacion ni filtros que
 *       puedan ocultar cargos: una vista parcial no es una verdad.</li>
 *   <li>Se incluyen cargos anulados, pendientes, parciales y pagados: el conjunto es el que hay.</li>
 *   <li>El conjunto se devuelve <strong>completo o no se devuelve</strong>: si un cargo no es
 *       verificable para el actor, la respuesta es {@code 409 FINANCIAL_CONTEXT_UNAVAILABLE} sin
 *       filtrar datos sensibles.</li>
 *   <li><strong>No se selecciona automaticamente un cargo</strong>, <strong>no se suman saldos
 *       entre cargos</strong> y <strong>no se buscan cargos sin cita</strong> por aproximacion.</li>
 * </ul>
 *
 * <p>La transaccion es de solo lectura con aislamiento {@code REPEATABLE_READ}: el conjunto de
 * cargos, sus conceptos y sus movimientos se leen de una fotografia coherente, sin paginas
 * truncadas ni saldos que no cuadren con los movimientos mostrados.
 */
@Service
public class AppointmentFinancialContextService {

    private final AppointmentService appointmentService;
    private final ChargeRepository charges;
    private final FinancialContextAssembler assembler;
    private final FinancialContextTokenBuilder tokens;
    private final Clock clock;

    public AppointmentFinancialContextService(AppointmentService appointmentService,
            ChargeRepository charges, FinancialContextAssembler assembler,
            FinancialContextTokenBuilder tokens, Clock clock) {
        this.appointmentService = appointmentService;
        this.charges = charges;
        this.assembler = assembler;
        this.tokens = tokens;
        this.clock = clock;
    }

    /**
     * Contexto financiero completo de una atencion.
     *
     * @throws ForbiddenOperationException si el rol no tiene acceso financiero (PATIENT) o si un
     *                                     {@code DENTIST} no demuestra pertenencia con perfil
     *                                     profesional
     * @throws NotFoundException           si la cita no existe o no es del alcance del actor
     * @throws FinancialContextUnavailableException si el conjunto de cargos no puede verificarse
     *                                             integramente
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public FinancialContext contextFor(Long appointmentId, AuthenticatedUser actor) {
        requireFinancialReader(actor);
        Instant observedAt = clock.instant();
        Appointment appointment = appointmentService.get(appointmentId, actor);
        List<Charge> associated = charges.findByAppointmentIdOrderByIdAsc(appointmentId);
        List<FinancialContextCharge> contextCharges = assembler.verifyAll(appointmentId,
                appointment.getPatientId(), associated, actor);
        return new FinancialContext(appointment, observedAt, classify(contextCharges),
                List.copyOf(contextCharges),
                tokens.tokenFor(appointment, contextCharges));
    }

    /**
     * El {@code PATIENT} no tiene acceso financiero en ningun endpoint (C-03). Se comprueba aqui y
     * no solo en la matriz HTTP, para que una llamada directa al servicio tampoco lo conceda.
     */
    private static void requireFinancialReader(AuthenticatedUser actor) {
        if (actor == null || actor.role() == null || actor.userId() == null || actor.userId() <= 0) {
            throw new ForbiddenOperationException("No hay identidad autenticada con rol.");
        }
        if (actor.role() == Role.PATIENT) {
            throw new ForbiddenOperationException("El rol PACIENTE no accede a informacion financiera.");
        }
    }

    /**
     * Clasificacion de cardinalidad. No elige cargo ni suma importes: solo describe la forma del
     * conjunto (D-01 sigue abierta).
     */
    private static ChargeAssociationState classify(List<FinancialContextCharge> contextCharges) {
        if (contextCharges.isEmpty()) {
            return ChargeAssociationState.NO_ASSOCIATED_CHARGE;
        }
        boolean anyActive = contextCharges.stream()
                .anyMatch(context -> context.state().status() != ChargeStatus.ANULADO);
        if (!anyActive) {
            return ChargeAssociationState.ONLY_CANCELLED;
        }
        if (contextCharges.stream().filter(context -> !context.charge().isCancelled()).count() == 1) {
            return ChargeAssociationState.SINGLE_ACTIVE;
        }
        return ChargeAssociationState.MULTIPLE_ACTIVE;
    }

    /**
     * Contexto completo de una atencion, ya verificado. {@code financialContextToken} es un valor
     * aditivo de frescura calculado despues de verificar el conjunto; no sustituye la autorizacion
     * ni contiene datos secretos.
     */
    public record FinancialContext(Appointment appointment, Instant observedAt,
            ChargeAssociationState associationState, List<FinancialContextCharge> charges,
            String financialContextToken) {
    }

    /** Un cargo del contexto, con su estado derivado, sus conceptos y sus movimientos. */
    public record FinancialContextCharge(Charge charge, ChargeFinancialState state,
            List<ChargeItem> items, List<FinancialMovement> movements) {
    }
}
