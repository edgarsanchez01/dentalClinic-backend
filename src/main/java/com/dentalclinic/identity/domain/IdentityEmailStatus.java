package com.dentalclinic.identity.domain;

/**
 * Estado de entrega de una fila de la outbox de identidad (F8B.2). Se persiste como cadena; V018
 * lo limita por CHECK.
 *
 * <p>Es un estado de <strong>entrega</strong>, no de validez del enlace: una fila {@code PENDING}
 * invalidada por una emisión posterior conserva su estado y se marca con {@code superseded_at}
 * (que no es un estado, D-F6-20).
 */
public enum IdentityEmailStatus {

    /** Encolada, pendiente de reclamar y enviar. */
    PENDING,

    /** El proveedor confirmó el envío (única transición terminal de éxito). */
    SENT,

    /** Se agotaron los intentos; queda registrada para intervención. */
    FAILED
}
