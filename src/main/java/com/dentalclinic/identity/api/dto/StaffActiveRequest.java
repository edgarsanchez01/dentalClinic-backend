package com.dentalclinic.identity.api.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Activación / desactivación administrativa de una cuenta de personal ({@code PATCH
 * /api/users/{id}/active}, Fase 8A / RF-PR-05). Espejo de {@code ServiceActiveRequest} del
 * catálogo: el cuerpo solo decide el estado; las reglas (self, último OWNER, cuentas pendientes)
 * viven en el servicio de aplicación.
 */
public record StaffActiveRequest(

        @NotNull(message = "Indica si la cuenta debe quedar activa.")
        Boolean active) {
}
