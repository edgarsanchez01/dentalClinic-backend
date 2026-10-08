package com.dentalclinic.identity.domain;

/**
 * Los cuatro roles oficiales del producto. Ni uno mas.
 *
 * <p>PRD.md §5.1: "Cuatro roles. Ninguno mas. Si una necesidad exige un quinto
 * rol, es una señal de que debe modelarse como permiso o flag, no como rol."
 *
 * <p>AD-18: {@code role} es NOT NULL con CHECK en el conjunto válido, de modo que
 * no existe el estado "usuario sin rol" (H-02).
 */
public enum Role {

    /** Único con autoridad administrativa total. Alcance administrativo, no clínico. */
    OWNER,

    /** Profesional odontológico tratante. Único con escritura clínica. */
    DENTIST,

    /** Personal de recepción/administración. Nunca accede a información clínica sensible. */
    RECEPTIONIST,

    /** Paciente con cuenta. Solo accede a sus propios datos. */
    PATIENT
}