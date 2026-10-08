package com.dentalclinic.identity.domain.exception;

/**
 * El email ya pertenece a una cuenta de la instalación ({@code users.email} UNIQUE,
 * Architecture.md §12.7). Se traduce a 409: es un conflicto de negocio, no un error de
 * formato (Architecture.md §8.2).
 */
public class EmailAlreadyRegisteredException extends RuntimeException {

    public EmailAlreadyRegisteredException() {
        super("Ya existe una cuenta con ese correo electrónico.");
    }
}
