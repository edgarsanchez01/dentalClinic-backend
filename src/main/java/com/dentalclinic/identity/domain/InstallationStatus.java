package com.dentalclinic.identity.domain;

/**
 * Estado de la instalación (PRD.md §6.6). Son los dos términos normativos y no existe ningún otro:
 * inventar estados intermedios ("en progreso", "parcial") sería añadir máquina de estados que el
 * producto no describe.
 */
public enum InstallationStatus {

    /** No existe fila en {@code installation_state}: aún no se ha ejecutado el onboarding. */
    AWAITING_FIRST_USER,

    /** El onboarding se completó y existe la clínica con su OWNER. */
    COMPLETED
}