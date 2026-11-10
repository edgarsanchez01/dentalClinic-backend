package com.dentalclinic.odontogram.domain;

/**
 * Dentición a la que pertenece una pieza dental (RF-OD-01, Architecture.md §11.2).
 *
 * <p>Se deriva de la propia notación FDI: los cuadrantes 1–4 son permanentes y los 5–8,
 * temporales. No se almacena como dato independiente para no duplicar una información que ya
 * contiene el código FDI.
 */
public enum Dentition {
    PERMANENT,
    TEMPORARY
}
