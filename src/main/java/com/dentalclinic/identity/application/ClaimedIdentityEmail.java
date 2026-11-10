package com.dentalclinic.identity.application;

import java.time.Instant;

import com.dentalclinic.identity.domain.IdentityEmailPurpose;

/**
 * Vista inmutable de una entrega de identidad reclamada por el dispatcher (F8B.2).
 *
 * <p>Se forma dentro de la transacción corta que reserva la fila; el envío SMTP ocurre después, con
 * esta copia ya separada de la entidad. {@code attempts} + {@code leaseUntil} identifican la reserva
 * para que un worker obsoleto no pise el resultado de uno posterior.
 *
 * <p>Nunca contiene el token en claro ni la URL: solo el nonce con el que el compositor lo
 * recompone y el hash con el que lo verifica.
 */
public record ClaimedIdentityEmail(Long id, Long userId, IdentityEmailPurpose purpose,
        String recipientEmail, String tokenNonce, String tokenHash, int attempts, Instant leaseUntil) {
    @Override
    public String toString() {
        return "ClaimedIdentityEmail{id=" + id + ", purpose=" + purpose + ", attempts=" + attempts + "}";
    }
}
