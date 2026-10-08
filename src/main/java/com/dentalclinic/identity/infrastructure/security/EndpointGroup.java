package com.dentalclinic.identity.infrastructure.security;

import com.dentalclinic.identity.domain.Role;

/**
 * Grupos de endpoints y su acceso por rol.
 *
 * <p>Es una <strong>transcripción literal de la matriz de Architecture.md §7.4</strong>, que es
 * normativa (PRD.md §5.3: "El backend la implementa; el frontend solo la refleja"). No hay aquí
 * ninguna regla inventada: cada celda procede de la tabla, incluidas las excepciones explícitas
 * como {@code /api/users/owners} sin acceso para nadie.
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

    /**
     * Historial clínico de un paciente. Solo el personal clínico lo lee y escribe.
     *
     * <p><strong>Debe declararse antes que {@link #PATIENTS}</strong>. La matriz da al paciente
     * {@code SELF_READ} sobre la gestión de pacientes, pero le niega el acceso al historial
     * clínico. Si este grupo no existiera, el filtro resolvería primero el patrón general y
     * dejaría que un paciente leyera su propio historial clínico a través de la ruta de gestión.
     */
    PATIENTS_CLINICAL("/api/patients/*/clinical/**",
            AccessLevel.READ, AccessLevel.READ_WRITE, AccessLevel.NONE, AccessLevel.NONE),

    /**
     * Odontograma de un paciente: el paciente ve su propio resumen y el personal clínico lo
     * gestiona. También debe resolverse antes que {@link #PATIENTS}.
     */
    PATIENTS_ODONTOGRAM("/api/patients/*/odontogram/**",
            AccessLevel.READ, AccessLevel.READ_WRITE, AccessLevel.NONE, AccessLevel.SELF_READ),

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

    /** El paciente lee las suyas y puede crear las suyas; nunca las de otro. */
    APPOINTMENTS("/api/appointments/**",
            AccessLevel.READ_WRITE, AccessLevel.READ_WRITE, AccessLevel.READ_WRITE, AccessLevel.SELF_READ_WRITE),

    /** OWNER gestiona el catálogo; DENTIST también; RECEPTIONIST solo lo consulta. */
    TREATMENTS("/api/treatments/**",
            AccessLevel.READ_WRITE, AccessLevel.READ_WRITE, AccessLevel.READ, AccessLevel.NONE),

    PAYMENTS("/api/payments/**",
            AccessLevel.READ_WRITE, AccessLevel.NONE, AccessLevel.READ_WRITE, AccessLevel.NONE),

    /** Los cuatro roles gestionan, pero solo sus propias notificaciones. */
    NOTIFICATIONS("/api/notifications/**",
            AccessLevel.SELF_READ_WRITE, AccessLevel.SELF_READ_WRITE, AccessLevel.SELF_READ_WRITE, AccessLevel.SELF_READ_WRITE),

    /** Solo OWNER: auditoría de comunicaciones, reintentos y diagnóstico técnico. */
    COMMUNICATIONS("/api/communications/**",
            AccessLevel.READ_WRITE, AccessLevel.NONE, AccessLevel.NONE, AccessLevel.NONE),

    FEEDBACK("/api/feedback/**",
            AccessLevel.READ, AccessLevel.READ, AccessLevel.NONE, AccessLevel.SELF_READ);

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