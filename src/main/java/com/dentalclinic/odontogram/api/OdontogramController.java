package com.dentalclinic.odontogram.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.dentalclinic.identity.domain.Role;
import com.dentalclinic.identity.infrastructure.security.AuthenticatedUser;
import com.dentalclinic.identity.infrastructure.security.CurrentUser;
import com.dentalclinic.odontogram.api.dto.CreateToothRecordRequest;
import com.dentalclinic.odontogram.api.dto.CreateToothSurfaceRequest;
import com.dentalclinic.odontogram.api.dto.OdontogramSummaryResponse;
import com.dentalclinic.odontogram.api.dto.ToothRecordResponse;
import com.dentalclinic.odontogram.api.dto.ToothSurfaceResponse;
import com.dentalclinic.odontogram.api.dto.ToothSurfaceSummaryResponse;
import com.dentalclinic.odontogram.api.dto.UpdateToothRecordRequest;
import com.dentalclinic.odontogram.api.dto.UpdateToothSurfaceRequest;
import com.dentalclinic.odontogram.application.OdontogramService;
import com.dentalclinic.odontogram.application.OdontogramService.ToothView;
import com.dentalclinic.odontogram.application.OdontogramService.ToothSurfaceView;
import com.dentalclinic.odontogram.domain.ToothSurface;

import jakarta.validation.Valid;

/**
 * Odontograma de un paciente (matriz §7.4, grupo {@code /api/patients/{id}/odontogram}).
 *
 * <p>OWNER y DENTIST leen y escriben (el OWNER siempre tiene perfil de dentista, AD-11);
 * RECEPTIONIST recibe 403 y el PATIENT solo lee el <strong>resumen</strong> de su propio
 * odontograma (RF-OD-08, P-05): el filtro de la matriz aplica el rol y el servicio verifica la
 * propiedad.
 */
@RestController
@RequestMapping("/api/patients/{id}/odontogram")
public class OdontogramController {

    private final OdontogramService odontogramService;

    public OdontogramController(OdontogramService odontogramService) {
        this.odontogramService = odontogramService;
    }

    @GetMapping
    public ResponseEntity<List<?>> list(@PathVariable("id") Long patientId) {
        AuthenticatedUser actor = CurrentUser.require();
        List<ToothView> views = odontogramService.list(patientId, actor);
        return ResponseEntity.ok(readView(actor, views));
    }

    @GetMapping("/{toothFdi}")
    public ResponseEntity<?> get(@PathVariable("id") Long patientId,
                                 @PathVariable("toothFdi") int toothFdi) {
        AuthenticatedUser actor = CurrentUser.require();
        ToothView view = odontogramService.get(patientId, toothFdi, actor);
        if (actor.role() == Role.PATIENT) {
            return ResponseEntity.ok(OdontogramSummaryResponse.from(view));
        }
        return ResponseEntity.ok(ToothRecordResponse.from(view));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ToothRecordResponse create(@PathVariable("id") Long patientId,
                                      @Valid @RequestBody CreateToothRecordRequest request) {
        AuthenticatedUser actor = CurrentUser.require();
        return ToothRecordResponse.from(odontogramService.create(patientId, request.toData(), actor,
                request.appointmentContext() == null ? null : request.appointmentContext().toSelection()));
    }

    @PutMapping("/{toothFdi}")
    public ToothRecordResponse update(@PathVariable("id") Long patientId,
                                      @PathVariable("toothFdi") int toothFdi,
                                      @Valid @RequestBody UpdateToothRecordRequest request) {
        AuthenticatedUser actor = CurrentUser.require();
        return ToothRecordResponse.from(
                odontogramService.update(patientId, toothFdi, request.toData(toothFdi), actor,
                        request.appointmentContext() == null ? null : request.appointmentContext().toSelection()));
    }

    @GetMapping("/{toothFdi}/surfaces")
    public ResponseEntity<List<?>> listSurfaces(@PathVariable("id") Long patientId,
                                                @PathVariable("toothFdi") int toothFdi) {
        AuthenticatedUser actor = CurrentUser.require();
        List<ToothSurfaceView> views = odontogramService.listSurfaces(patientId, toothFdi, actor);
        return ResponseEntity.ok(readSurfaceView(actor, views));
    }

    @GetMapping("/{toothFdi}/surfaces/{surface}")
    public ResponseEntity<?> getSurface(@PathVariable("id") Long patientId,
                                        @PathVariable("toothFdi") int toothFdi,
                                        @PathVariable("surface") ToothSurface surface) {
        AuthenticatedUser actor = CurrentUser.require();
        ToothSurfaceView view = odontogramService.getSurface(patientId, toothFdi, surface, actor);
        if (actor.role() == Role.PATIENT) {
            return ResponseEntity.ok(ToothSurfaceSummaryResponse.from(view));
        }
        return ResponseEntity.ok(ToothSurfaceResponse.from(view));
    }

    @PostMapping("/{toothFdi}/surfaces")
    @ResponseStatus(HttpStatus.CREATED)
    public ToothSurfaceResponse createSurface(@PathVariable("id") Long patientId,
                                              @PathVariable("toothFdi") int toothFdi,
                                              @Valid @RequestBody CreateToothSurfaceRequest request) {
        AuthenticatedUser actor = CurrentUser.require();
        return ToothSurfaceResponse.from(
                odontogramService.createSurface(patientId, toothFdi, request.toData(), actor,
                        request.appointmentContext() == null ? null : request.appointmentContext().toSelection()));
    }

    @PutMapping("/{toothFdi}/surfaces/{surface}")
    public ToothSurfaceResponse updateSurface(@PathVariable("id") Long patientId,
                                              @PathVariable("toothFdi") int toothFdi,
                                              @PathVariable("surface") ToothSurface surface,
                                              @Valid @RequestBody UpdateToothSurfaceRequest request) {
        AuthenticatedUser actor = CurrentUser.require();
        return ToothSurfaceResponse.from(odontogramService.updateSurface(patientId, toothFdi, surface,
                request.condition(), request.notes(), actor,
                request.appointmentContext() == null ? null : request.appointmentContext().toSelection()));
    }

    /** El PATIENT recibe el resumen reducido; el personal clínico, la respuesta completa. */
    private static List<?> readView(AuthenticatedUser actor, List<ToothView> views) {
        if (actor.role() == Role.PATIENT) {
            return views.stream().map(OdontogramSummaryResponse::from).toList();
        }
        return views.stream().map(ToothRecordResponse::from).toList();
    }

    /** El PATIENT recibe el resumen reducido de superficie; el personal clínico, la respuesta completa. */
    private static List<?> readSurfaceView(AuthenticatedUser actor, List<ToothSurfaceView> views) {
        if (actor.role() == Role.PATIENT) {
            return views.stream().map(ToothSurfaceSummaryResponse::from).toList();
        }
        return views.stream().map(ToothSurfaceResponse::from).toList();
    }
}
