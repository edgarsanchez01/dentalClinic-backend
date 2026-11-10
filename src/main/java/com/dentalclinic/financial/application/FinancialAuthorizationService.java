package com.dentalclinic.financial.application;

import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dentalclinic.appointment.application.AppointmentService;
import com.dentalclinic.appointment.domain.Appointment;
import com.dentalclinic.clinic.application.DentistService;
import com.dentalclinic.financial.application.command.ConfirmChargeCommand;
import com.dentalclinic.financial.domain.Charge;
import com.dentalclinic.financial.domain.ChargeItem;
import com.dentalclinic.financial.domain.FinancialIntentOperation;
import com.dentalclinic.financial.infrastructure.repository.ChargeItemRepository;
import com.dentalclinic.identity.domain.Role;
import com.dentalclinic.identity.domain.exception.ForbiddenOperationException;
import com.dentalclinic.identity.domain.exception.NotFoundException;
import com.dentalclinic.identity.infrastructure.security.AuthenticatedUser;

/**
 * Autorizacion financiera por rol y por pertenencia segun la matriz definitiva de Fase 4B.
 *
 * <p>La autorizacion por rol (grueso) la aplica la cadena de seguridad sobre la URL; este servicio
 * resuelve el acceso fino de consulta y de escritura. El filtro de URL de cargos refleja los roles;
 * la pertenencia se comprueba aqui y en el predicado de listados.
 *
 * <p><strong>Matriz definitiva (Fase 4B):</strong>
 *
 * <ul>
 *   <li>{@code OWNER}: acceso global; crea cargos, registra pagos y es el unico que anula cargos y
 *       reversa pagos.</li>
 *   <li>{@code RECEPTIONIST}: acceso global; crea cargos y registra pagos, NO anula ni reversa.</li>
 *   <li>{@code DENTIST}: <strong>solo consulta</strong> cargos y movimientos de sus propias
 *       atenciones verificadas. NO crea cargos, NO registra pagos, NO anula ni reversa. No accede a
 *       cargos ajenos, mixtos o sin vinculo verificable.</li>
 *   <li>{@code PATIENT}: sin acceso financiero.</li>
 * </ul>
 *
 * <p><strong>Nunca se confia en un {@code dentistId} del cliente.</strong> La identidad se resuelve
 * del contexto autenticado y la vinculacion se verifica contra datos persistidos. Que un
 * {@code charge_item} tenga {@code treating_dentist_id} no basta por si solo.
 *
 * <p><strong>Fail-closed:</strong> si no puede demostrarse una relacion autorizada, se deniega.
 *
 * <p>La denegacion de escritura a DENTIST ocurre aqui, en la capa de aplicacion, ademas de en la
 * matriz HTTP ({@code EndpointGroup}), de modo que una invocacion directa del servicio tampoco
 * concede escritura.
 */
@Service
public class FinancialAuthorizationService {

    private final DentistService dentistService;
    private final AppointmentService appointmentService;
    private final ChargeItemRepository chargeItemRepository;

    public FinancialAuthorizationService(DentistService dentistService,
                                         AppointmentService appointmentService,
                                         ChargeItemRepository chargeItemRepository) {
        this.dentistService = dentistService;
        this.appointmentService = appointmentService;
        this.chargeItemRepository = chargeItemRepository;
    }

    /**
     * Exige que el actor pueda ver el cargo.
     *
     * @throws ForbiddenOperationException si el ROL no tiene acceso financiero (PATIENT)
     * @throws NotFoundException           si el rol puede acceder, pero el cargo no es visible para
     *                                     el actor (se prefiere 404 a 403 para no revelar la
     *                                     existencia de recursos ajenos, §8.2)
     */
    @Transactional(readOnly = true)
    public void requireView(AuthenticatedUser actor, Charge charge) {
        Objects.requireNonNull(charge, "charge es obligatorio");
        Role role = roleOf(actor);
        switch (role) {
            case OWNER, RECEPTIONIST -> {
                // Acceso global.
            }
            case PATIENT -> throw new ForbiddenOperationException(
                    "El rol PACIENTE no accede a informacion financiera.");
            case DENTIST -> {
                if (!dentistOwnsCharge(actor, charge)) {
                    throw new NotFoundException();
                }
            }
        }
    }

    /** Variante booleana de {@link #requireView} para filtrar listados. */
    @Transactional(readOnly = true)
    public boolean canView(AuthenticatedUser actor, Charge charge) {
        try {
            requireView(actor, charge);
            return true;
        } catch (ForbiddenOperationException | NotFoundException denied) {
            return false;
        }
    }

    /** Alcance de listado resuelto desde identidad persistida, nunca desde un filtro del cliente. */
    @Transactional(readOnly = true)
    public ReadScope listScope(AuthenticatedUser actor) {
        return switch (roleOf(actor)) {
            case OWNER, RECEPTIONIST -> new ReadScope(true, null);
            case DENTIST -> new ReadScope(false, dentistService.dentistIdFor(actor.userId()).orElse(null));
            case PATIENT -> throw new ForbiddenOperationException(
                    "El rol PACIENTE no accede a informacion financiera.");
        };
    }

    public record ReadScope(boolean global, Long dentistId) { }

    /**
     * Autoriza el alta del cargo antes de persistir: exclusivamente {@code OWNER} o
     * {@code RECEPTIONIST}. {@code DENTIST} y {@code PATIENT} no crean cargos y reciben 403. 2B
     * valida ademas referencias e importes y guarda en la misma transaccion los conceptos
     * autorizados. Este metodo no crea el cargo.
     */
    public void requireCreateCharge(AuthenticatedUser actor, ConfirmChargeCommand command) {
        Objects.requireNonNull(command, "command es obligatorio");
        requireChargeWriteRole(roleOf(actor), "crear cargos");
    }

    /** Autoriza registrar un pago sobre un cargo persistido; no registra ningun movimiento. */
    public void requireRegisterPayment(AuthenticatedUser actor, Charge charge) {
        Objects.requireNonNull(charge, "charge es obligatorio");
        requireChargeWriteRole(roleOf(actor), "registrar pagos");
    }

    /** Escritura de cargos: solo OWNER y RECEPTIONIST. DENTIST y PATIENT quedan fuera. */
    private static void requireChargeWriteRole(Role role, String action) {
        if (role != Role.OWNER && role != Role.RECEPTIONIST) {
            throw new ForbiddenOperationException(
                    "Solo OWNER o RECEPTIONIST pueden " + action + ".");
        }
    }

    /** Anulacion de cargos: exclusivamente OWNER. */
    public void requireCancel(AuthenticatedUser actor) {
        if (roleOf(actor) != Role.OWNER) {
            throw new ForbiddenOperationException("Solo OWNER puede anular cargos.");
        }
    }

    /** Reversa de pagos: exclusivamente OWNER. */
    public void requireReverse(AuthenticatedUser actor) {
        if (roleOf(actor) != Role.OWNER) {
            throw new ForbiddenOperationException("Solo OWNER puede reversar pagos.");
        }
    }

    /** Gate de registro AD-34; no valida existencia/saldo ni concede permiso de ejecucion. */
    public void requireIntentRegistration(AuthenticatedUser actor, FinancialIntentOperation operation) {
        switch (Objects.requireNonNull(operation, "operation es obligatoria")) {
            case FIN_CONFIRM_CHARGE, FIN_REGISTER_PAYMENT ->
                    requireChargeWriteRole(roleOf(actor), "registrar intenciones financieras");
            case FIN_REVERSE_PAYMENT -> requireReverse(actor);
            case FIN_CANCEL_CHARGE -> requireCancel(actor);
        }
    }

    /**
     * Reportes financieros administrativos: solo {@code OWNER} y {@code RECEPTIONIST}. Aunque son de
     * solo lectura, son <strong>globales</strong> (agregan cartera y cobros de todo el consultorio),
     * por lo que {@code DENTIST} —que solo consulta sus cargos propios— y {@code PATIENT} quedan
     * fuera. No concede ninguna escritura.
     */
    @Transactional(readOnly = true)
    public void requireReports(AuthenticatedUser actor) {
        Role role = roleOf(actor);
        if (role != Role.OWNER && role != Role.RECEPTIONIST) {
            throw new ForbiddenOperationException(
                    "Solo OWNER o RECEPTIONIST pueden consultar reportes financieros.");
        }
    }

    private static Role roleOf(AuthenticatedUser actor) {
        if (actor == null || actor.role() == null || actor.userId() == null
                || actor.userId() <= 0) {
            throw new ForbiddenOperationException("No hay identidad autenticada con rol.");
        }
        return actor.role();
    }

    /**
     * Un {@code DENTIST} consulta/registra pagos solo si TODAS estas condiciones se cumplen sobre datos
     * persistidos:
     *
     * <ol>
     *   <li>tiene perfil profesional ({@code dentist}) resuelto por su {@code userId};</li>
     *   <li>el cargo referencia una cita ({@code appointmentId != null}): sin cita NO existe una
     *       relacion confiable en el esquema, por lo que se deniega (fail-closed);</li>
     *   <li>la cita existe, pertenece al mismo paciente y su profesional es el propio dentista;</li>
     *   <li>hay conceptos persistidos y ninguno apunta a otro dentista (cargo no mixto).</li>
     * </ol>
     *
     * <p>Reconocimiento explicito de limite: {@code treatment} no guarda dentista y las notas/
     * evoluciones clinicas no se vinculan a cargos, por lo que hoy no hay relacion alternativa a la
     * cita. Un cargo sin cita queda reservado a OWNER/RECEPTIONIST.
     */
    private boolean dentistOwnsCharge(AuthenticatedUser actor, Charge charge) {
        if (charge.getId() == null) {
            return false;
        }
        Long dentistId = dentistService.dentistIdFor(actor.userId()).orElse(null);
        if (!ownsAppointment(actor, charge.getPatientId(), charge.getAppointmentId(), dentistId)) {
            return false;
        }
        List<ChargeItem> items = chargeItemRepository.findByChargeIdOrderByIdAsc(charge.getId());
        if (items.isEmpty()) {
            return false;
        }
        for (ChargeItem item : items) {
            if (!charge.getId().equals(item.getChargeId())
                    || (item.getTreatingDentistId() != null
                        && !dentistId.equals(item.getTreatingDentistId()))) {
                return false;
            }
        }
        return true;
    }

    private boolean ownsAppointment(AuthenticatedUser actor, Long patientId, Long appointmentId,
                                    Long dentistId) {
        if (dentistId == null || appointmentId == null || patientId == null) {
            return false;
        }
        Appointment appointment = appointmentService.get(appointmentId, actor);
        return dentistId.equals(appointment.getDentistId())
                && patientId.equals(appointment.getPatientId());
    }
}
