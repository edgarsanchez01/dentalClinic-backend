package com.dentalclinic.catalog.domain;

public class ServiceUnavailableException extends RuntimeException {
    public ServiceUnavailableException() {
        super("El servicio seleccionado ya no está activo. Actualiza el catálogo y selecciona otro servicio.");
    }
}
