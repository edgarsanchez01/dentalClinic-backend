package com.dentalclinic.financial.api;

import jakarta.validation.constraints.Positive;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.dentalclinic.financial.api.dto.AppointmentFinancialContextResponse;
import com.dentalclinic.financial.application.AppointmentFinancialContextService;
import com.dentalclinic.identity.infrastructure.security.CurrentUser;

/**
 * Contexto financiero de una atencion: <strong>solo lectura</strong> (UX-05B.1, Etapa 2).
 *
 * <p>Vive en el modulo financiero pero se publica bajo {@code /api/appointments}: la ruta es la
 * cita, el contenido es financiero. Su grupo de seguridad es propio y se resuelve <strong>antes</strong>
 * del grupo general de citas, de modo que el {@code PATIENT} no herede el acceso de agenda.
 *
 * <p>La autorizacion fina la aplica el servicio (alcance de la cita + visibilidad de cada cargo);
 * aqui solo se traduce el transporte. La respuesta lleva {@code Cache-Control: no-store}: es
 * informacion financiera sensible y no debe quedar en caches intermedias.
 */
@RestController
@RequestMapping("/api/appointments/{appointmentId}/financial-context")
public class AppointmentFinancialContextController {

    private final AppointmentFinancialContextService contexts;

    public AppointmentFinancialContextController(AppointmentFinancialContextService contexts) {
        this.contexts = contexts;
    }

    @GetMapping
    public ResponseEntity<AppointmentFinancialContextResponse> context(
            @PathVariable @Positive Long appointmentId) {
        AppointmentFinancialContextResponse response = AppointmentFinancialContextResponse.from(
                contexts.contextFor(appointmentId, CurrentUser.require()));
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(response);
    }
}
