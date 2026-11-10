package com.dentalclinic.odontogram.domain;

/**
 * Condición de una pieza dental (RF-OD-03, Architecture.md §11.2, PRD §19.1).
 *
 * <p>Conjunto cerrado. No hay condiciones auxiliares ni estados intermedios inventados: la
 * gestión de la pieza consiste en fijar una de estas condiciones.
 */
public enum ToothCondition {
    HEALTHY,
    CARIES,
    FRACTURED,
    RESTORED,
    ROOT_CANAL,
    CROWN,
    IMPLANT,
    MISSING,
    EXTRACTED,
    SEALED,
    OTHER
}
