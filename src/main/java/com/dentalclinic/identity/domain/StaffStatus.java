package com.dentalclinic.identity.domain;

/**
 * Estado administrativo de una cuenta de personal en el listado {@code GET /api/users}
 * (Fase 8A).
 *
 * <p>No es una columna ni un estado persistido: es la clasificación <em>derivada</em> que
 * distingue lo que {@code User.active} por sí solo no puede expresar. {@code active = false} abarca
 * dos realidades distintas y con acciones distintas:
 *
 * <ul>
 *   <li>{@code PENDING} — la cuenta nació por invitación y <strong>nunca</strong> se activó: tiene
 *       una invitación sin usar. Su acceso se gestiona con el enlace de activación; no hay
 *       sesión que retirar.</li>
 *   <li>{@code INACTIVE} — la cuenta ya estuvo activa y fue desactivada (kill-switch §7.1, RF-PR-05):
 *       su invitación está usada (o no existe). Se recupera con la reactivación administrativa.</li>
 * </ul>
 *
 * <p>{@code ACTIVE} es la cuenta activada normal.
 */
public enum StaffStatus {
    ACTIVE,
    PENDING,
    INACTIVE
}
