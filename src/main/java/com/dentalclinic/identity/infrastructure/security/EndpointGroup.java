package com.dentalclinic.identity.infrastructure.security;

import com.dentalclinic.identity.domain.Role;

/**
 * Grupos de endpoints y su acceso por rol.
 *
 * <p>Representa la matriz de Architecture.md §7.4 y la autorizacion financiera posterior de Fase 3.
 * Los permisos de cargos son globales para OWNER/RECEPTIONIST y propios para DENTIST;
 * las acciones de correccion se restringen antes de resolver el grupo general de cargos.
 *
 * <p><strong>El orden de las constantes importa</strong> y es de más específico a más general.
 * El grupo de historial clínico de un paciente debe resolverse <em>antes</em> que el grupo
 * general de pacientes, y al revés devolvería a un paciente {@code READ} sobre un grupo que solo
 * el personal clínico debe leer. La primera coincidencia gana.
 */
public enum EndpointGroup {

    /** Configuración de la clínica. Solo OWNER (matriz §7.4 fila 1: {@code rw / — / — / —}). */
    CLINIC("/api/clinic",
            AccessLevel.READ_WRITE, AccessLevel.NONE, AccessLevel.NONE, AccessLevel.NONE),

    /**
     * Matriz §7.4: ningún rol, ni siquiera OWNER, puede crear una segunda cuenta OWNER (P-07).
     *
     * <p><strong>Debe declararse antes que {@link #USERS}.</strong> La primera coincidencia gana:
     * si {@code /api/users/**} se resolviera primero, reservaría {@code /api/users/owners} al
     * OWNER como {@code READ_WRITE} y la excepción explícita de la matriz quedaría anulada.
     */
    CREATE_OWNER("/api/users/owners",
            AccessLevel.NONE, AccessLevel.NONE, AccessLevel.NONE, AccessLevel.NONE),

    /**
     * Gestiona la configuración de la clínica y las cuentas del personal. Solo OWNER.
     * Solo lectura/escritura: el acceso ya está reservado a un único rol, no hace falta acotar por
     * propietario individual.
     *
     * <p>No cubre {@code /api/users/owners}: {@link #CREATE_OWNER}, declarado antes, lo niega a
     * todos los roles.
     */
    USERS("/api/users/**",
            AccessLevel.READ_WRITE, AccessLevel.NONE, AccessLevel.NONE, AccessLevel.NONE),

    DENTISTS("/api/dentists/**",
            AccessLevel.READ_WRITE, AccessLevel.READ, AccessLevel.READ, AccessLevel.NONE),

    /** Self-only intake, before general patients; no staff may inspect drafts. */
    PATIENT_INTAKE_SELF("/api/patients/me/medical-intake/**",
            AccessLevel.NONE, AccessLevel.NONE, AccessLevel.NONE, AccessLevel.SELF_READ_WRITE),

    /** Confirmed declarations are read-only even for clinical staff. */
    PATIENT_INTAKE_CLINICAL("/api/patients/*/clinical/medical-intake/**",
            AccessLevel.READ, AccessLevel.READ, AccessLevel.NONE, AccessLevel.NONE),

    /**
     * Historial clínico de un paciente. OWNER y DENTIST lo leen y lo escriben (el OWNER siempre
     * tiene perfil de dentista, AD-11); RECEPTIONIST y PATIENT no acceden.
     *
     * <p><strong>Debe declararse antes que {@link #PATIENTS}</strong>. La matriz da al paciente
     * {@code SELF_READ} sobre la gestión de pacientes, pero le niega el acceso al historial
     * clínico. Si este grupo no existiera, el filtro resolvería primero el patrón general y
     * dejaría que un paciente leyera su propio historial clínico a través de la ruta de gestión.
     */
    PATIENTS_CLINICAL("/api/patients/*/clinical/**",
            AccessLevel.READ_WRITE, AccessLevel.READ_WRITE, AccessLevel.NONE, AccessLevel.NONE),

    /** UX-05F: reduced current state only; never grant reception the clinical namespace. */
    PATIENTS_ODONTOGRAM_RECEPTION_SUMMARY("/api/patients/*/odontogram/reception-summary",
            AccessLevel.NONE, AccessLevel.NONE, AccessLevel.READ, AccessLevel.NONE),

    /**
     * Odontograma de un paciente: el paciente ve su propio resumen; OWNER y DENTIST lo gestionan
     * (el OWNER siempre tiene perfil de dentista, AD-11). También debe resolverse antes que
     * {@link #PATIENTS}.
     */
    PATIENTS_ODONTOGRAM("/api/patients/*/odontogram/**",
            AccessLevel.READ_WRITE, AccessLevel.READ_WRITE, AccessLevel.NONE, AccessLevel.SELF_READ),

    /**
     * Gestión de pacientes de la clínica. OWNER y RECEPTIONIST gestionan; DENTIST solo lee; el
     * paciente solo se lee a sí mismo.
     */
    PATIENTS("/api/patients/**",
            AccessLevel.READ_WRITE, AccessLevel.READ, AccessLevel.READ_WRITE, AccessLevel.SELF_READ),

    AVAILABILITY("/api/availability/**",
            AccessLevel.READ_WRITE, AccessLevel.READ_WRITE, AccessLevel.READ_WRITE, AccessLevel.READ),

    SCHEDULES_CLINIC("/api/schedules/clinic/**",
            AccessLevel.READ_WRITE, AccessLevel.READ, AccessLevel.READ, AccessLevel.NONE),

    SCHEDULES_DENTIST("/api/schedules/dentist/**",
            AccessLevel.READ_WRITE, AccessLevel.READ_WRITE, AccessLevel.READ, AccessLevel.NONE),

    /** UX-05C: integrated checkout is retired for every role and method. */
    APPOINTMENTS_CHECKOUT("/api/appointments/*/checkout",
            AccessLevel.NONE, AccessLevel.NONE, AccessLevel.NONE, AccessLevel.NONE),

    APPOINTMENTS_COLLECTION("/api/appointments/*/collection",
            AccessLevel.READ_WRITE, AccessLevel.NONE, AccessLevel.READ_WRITE, AccessLevel.NONE),

    APPOINTMENTS_COLLECTION_QUEUE("/api/appointments/collection-queue",
            AccessLevel.READ, AccessLevel.NONE, AccessLevel.READ, AccessLevel.NONE),

    APPOINTMENTS_COMPLETE("/api/appointments/*/complete",
            AccessLevel.READ_WRITE, AccessLevel.SELF_READ_WRITE, AccessLevel.NONE, AccessLevel.NONE),

    /**
     * Contexto financiero de una cita (UX-05B.1, Etapa 2 — <strong>solo lectura</strong>).
     *
     * <p><strong>Debe declararse antes que {@link #APPOINTMENTS}.</strong> La primera coincidencia
     * gana: si el grupo general de citas se resolviera primero, el patron {@code /api/appointments/**}
     * daria al {@code PATIENT} su acceso de agenda ({@code SELF_READ_WRITE}) sobre esta ruta
     * financiera. Este grupo lo nega explicitamente.
     *
     * <ul>
     *   <li>{@code OWNER} y {@code RECEPTIONIST}: lectura administrativa global.</li>
     *   <li>{@code DENTIST}: {@link AccessLevel#SELF_READ}: solo el contexto de <strong>su propia</strong>
     *       cita. La pertenencia se verifica en el servicio, que resuelve la cita con el alcance de
     *       agenda y exige ademas que el conjunto de cargos sea verificable por completo.</li>
     *   <li>{@code PATIENT}: sin acceso.</li>
     * </ul>
     *
     * <p>Es un grupo de <strong>lectura</strong>: cualquier escritura sobre esta ruta queda
     * denegada por rol y por metodo. El checkout tiene un grupo y un gate independientes.
     */
    APPOINTMENTS_FINANCIAL_CONTEXT("/api/appointments/*/financial-context",
            AccessLevel.READ, AccessLevel.SELF_READ, AccessLevel.READ, AccessLevel.NONE),

    /** El paciente lee las suyas y puede crear las suyas; nunca las de otro. */
    APPOINTMENTS("/api/appointments/**",
            AccessLevel.READ_WRITE, AccessLevel.READ_WRITE, AccessLevel.READ_WRITE, AccessLevel.SELF_READ_WRITE),

    /** Catálogo administrativo F7.1 (RF-TR-02). Separado de cualquier tratamiento clínico. */
    SERVICE_CATALOG("/api/services/**",
            AccessLevel.READ_WRITE, AccessLevel.READ, AccessLevel.READ, AccessLevel.NONE),

    /** Grupo histórico general de tratamientos; no cubre el catálogo administrativo F7.1. */
    TREATMENTS("/api/treatments/**",
            AccessLevel.READ_WRITE, AccessLevel.READ_WRITE, AccessLevel.READ, AccessLevel.NONE),

    /** Acciones financieras sensibles: resolver antes del grupo general de cargos. */
    CHARGE_REVERSALS("/api/charges/*/movements/*/reverse",
            AccessLevel.READ_WRITE, AccessLevel.NONE, AccessLevel.NONE, AccessLevel.NONE),

    CHARGE_CANCELLATIONS("/api/charges/*/cancel",
            AccessLevel.READ_WRITE, AccessLevel.NONE, AccessLevel.NONE, AccessLevel.NONE),

    /**
     * Cargos y pagos (Fase 4B): OWNER y RECEPTIONIST gestionan; DENTIST solo consulta sus propios
     * cargos verificados ({@link AccessLevel#SELF_READ}); PATIENT no accede. Las acciones sensibles
     * ({@link #CHARGE_REVERSALS}, {@link #CHARGE_CANCELLATIONS}) se resuelven antes y quedan en OWNER.
     *
     * <p>Un {@code POST} de DENTIST a este grupo (alta de cargo o registro de pago) es una escritura
     * sobre un nivel de solo lectura y se deniega con 403, ademas de la denegacion de aplicacion.
     */
    CHARGES("/api/charges/**",
            AccessLevel.READ_WRITE, AccessLevel.SELF_READ, AccessLevel.READ_WRITE, AccessLevel.NONE),

    /** AD-34 B4: siempre propias; el servicio restringe ademas operacion y recurso. */
    FINANCIAL_INTENTS("/api/financial-intents/**",
            AccessLevel.SELF_READ_WRITE, AccessLevel.NONE, AccessLevel.SELF_READ_WRITE, AccessLevel.NONE),

    /**
     * Reportes financieros administrativos (Fase 4C): solo lectura y solo OWNER/RECEPTIONIST. Son
     * globales (agregan cartera y cobros de todo el consultorio), por lo que DENTIST —limitado a sus
     * cargos propios— y PATIENT no acceden. {@link AccessLevel#READ} niega cualquier escritura.
     */
    FINANCIAL_REPORTS("/api/financial/reports/**",
            AccessLevel.READ, AccessLevel.NONE, AccessLevel.READ, AccessLevel.NONE),

    /** Grupo historico sin endpoints financieros nuevos; conserva sus permisos. */
    PAYMENTS("/api/payments/**",
            AccessLevel.READ_WRITE, AccessLevel.NONE, AccessLevel.READ_WRITE, AccessLevel.NONE),

    /** Los cuatro roles gestionan, pero solo sus propias notificaciones. */
    NOTIFICATIONS("/api/notifications/**",
            AccessLevel.SELF_READ_WRITE, AccessLevel.SELF_READ_WRITE, AccessLevel.SELF_READ_WRITE, AccessLevel.SELF_READ_WRITE),

    /** Solo OWNER: auditoría de comunicaciones, reintentos y diagnóstico técnico. */
    COMMUNICATIONS("/api/communications/**",
            AccessLevel.READ_WRITE, AccessLevel.NONE, AccessLevel.NONE, AccessLevel.NONE),

    FEEDBACK("/api/feedback/**",
            AccessLevel.READ, AccessLevel.SELF_READ, AccessLevel.NONE, AccessLevel.NONE);

    private final String pattern;
    private final AccessLevel owner;
    private final AccessLevel dentist;
    private final AccessLevel receptionist;
    private final AccessLevel patient;

    EndpointGroup(String pattern,
                  AccessLevel owner,
                  AccessLevel dentist,
                  AccessLevel receptionist,
                  AccessLevel patient) {
        this.pattern = pattern;
        this.owner = owner;
        this.dentist = dentist;
        this.receptionist = receptionist;
        this.patient = patient;
    }

    public String pattern() {
        return pattern;
    }

    /** Acceso del grupo para el rol indicado. */
    public AccessLevel levelFor(Role role) {
        return switch (role) {
            case OWNER -> owner;
            case DENTIST -> dentist;
            case RECEPTIONIST -> receptionist;
            case PATIENT -> patient;
        };
    }

    @Override
    public String toString() {
        return pattern;
    }
}
