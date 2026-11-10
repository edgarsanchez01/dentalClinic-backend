package com.dentalclinic.checkout.api;

import jakarta.validation.constraints.Positive;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.dentalclinic.checkout.api.dto.AppointmentCheckoutResponse;
import com.dentalclinic.checkout.application.CheckoutCoordinator;
import com.dentalclinic.identity.infrastructure.security.CurrentUser;

/**
 * Cierre integrado de una atencion: completar y, en la misma transaccion, crear o reutilizar un
 * cargo y registrar como maximo un pago nuevo.
 *
 * <p>La ruta es de cita pero el contenido es clinico-financiero. Su grupo de seguridad propio se
 * declara antes del grupo general de citas para que {@code PATIENT} y {@code RECEPTIONIST} no
 * hereden otros permisos. La autorizacion fina (DENTIST propietario, OWNER asignado) la repite el
 * participante de completado. El endpoint no cobra con tarjeta, no acepta importes fuente ni
 * campos desconocidos y responde siempre {@code Cache-Control: no-store}.
 */
@RestController
@RequestMapping("/api/appointments/{appointmentId}/checkout")
public class AppointmentCheckoutController {

    private final AppointmentCheckoutRequestParser parser;
    private final CheckoutCoordinator coordinator;

    public AppointmentCheckoutController(AppointmentCheckoutRequestParser parser,
            CheckoutCoordinator coordinator) {
        this.parser = parser;
        this.coordinator = coordinator;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AppointmentCheckoutResponse> checkout(
            @PathVariable @Positive Long appointmentId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) String body) {
        var command = parser.parse(appointmentId, idempotencyKey, body);
        AppointmentCheckoutResponse response = coordinator.checkout(command, CurrentUser.require());
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(response);
    }
}
