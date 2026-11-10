package com.dentalclinic.identity.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.dentalclinic.clinic.application.DentistService.DentistProfile;
import com.dentalclinic.identity.api.dto.CreateDentistRequest;
import com.dentalclinic.identity.api.dto.CreateReceptionistRequest;
import com.dentalclinic.identity.api.dto.StaffActiveRequest;
import com.dentalclinic.identity.api.dto.StaffUserDto;
import com.dentalclinic.identity.api.dto.StaffUserDto.DentistDto;
import com.dentalclinic.identity.api.dto.UpdateDentistRequest;
import com.dentalclinic.identity.application.StaffInvitationService;
import com.dentalclinic.identity.application.StaffInvitationService.NewDentist;
import com.dentalclinic.identity.application.StaffInvitationService.NewReceptionist;
import com.dentalclinic.identity.application.StaffInvitationService.StaffMemberView;
import com.dentalclinic.identity.application.StaffInvitationService.UpdateDentist;
import com.dentalclinic.identity.infrastructure.security.CurrentUser;

import jakarta.validation.Valid;

/**
 * Gestión del personal (matriz §7.4 fila "Users": solo OWNER).
 *
 * <p>Rutas normativas de Fase 3: {@code GET /api/users}, {@code POST /api/users/dentists} y
 * {@code POST /api/users/receptionists}. {@code POST /api/users/owners} queda denegado a todos los
 * roles por {@code EndpointGroup.CREATE_OWNER} (P-07 OPEN): no se crea un segundo OWNER.
 *
 * <p>Fase 8A (RF-PR-05/06): {@code PUT /api/users/{id}/dentist} edita los datos administrativos
 * del perfil profesional (sin email, rol ni contraseña) y {@code PATCH /api/users/{id}/active}
 * activa o desactiva cuentas. Ambas rutas quedan cubiertas por el grupo {@code USERS} existente
 * (solo OWNER); las reglas de negocio —autodesactivación, último OWNER, cuentas pendientes— viven
 * en {@link StaffInvitationService}, no en el controlador.
 *
 * <p>Las cuentas creadas nacen <strong>pendientes de activar</strong>; el invitado fija su
 * contraseña con el token de un solo uso en {@code POST /api/auth/activate} (F2).
 */
@RestController
@RequestMapping("/api/users")
public class UserManagementController {

    private final StaffInvitationService staffInvitationService;

    public UserManagementController(StaffInvitationService staffInvitationService) {
        this.staffInvitationService = staffInvitationService;
    }

    @GetMapping
    public List<StaffUserDto> list() {
        return staffInvitationService.listTeam().stream().map(UserManagementController::toDto).toList();
    }

    @PostMapping("/dentists")
    @ResponseStatus(HttpStatus.CREATED)
    public StaffUserDto createDentist(@Valid @RequestBody CreateDentistRequest request) {
        StaffMemberView member = staffInvitationService.inviteDentist(new NewDentist(
                request.email(), request.firstName(), request.lastName(), request.phone(),
                request.specialty(), request.licenseNumber(), request.publicInfo(), request.photoUrl()));
        return toDto(member);
    }

    @PostMapping("/receptionists")
    @ResponseStatus(HttpStatus.CREATED)
    public StaffUserDto createReceptionist(@Valid @RequestBody CreateReceptionistRequest request) {
        return toDto(staffInvitationService.inviteReceptionist(new NewReceptionist(request.email())));
    }

    /** Edición administrativa del perfil profesional (Fase 8A). Sin email, rol ni contraseña. */
    @PutMapping("/{id}/dentist")
    public StaffUserDto updateDentist(@PathVariable("id") Long id,
                                      @Valid @RequestBody UpdateDentistRequest request) {
        return toDto(staffInvitationService.updateDentistProfile(id, new UpdateDentist(
                request.firstName(), request.lastName(), request.phone(), request.specialty(),
                request.licenseNumber(), request.publicInfo(), request.photoUrl())));
    }

    /** Activación / desactivación de la cuenta (Fase 8A). El actor resuelto en servidor, nunca del body. */
    @PatchMapping("/{id}/active")
    public StaffUserDto setActive(@PathVariable("id") Long id,
                                  @Valid @RequestBody StaffActiveRequest request) {
        return toDto(staffInvitationService.setActive(id, request.active(), CurrentUser.require()));
    }

    private static StaffUserDto toDto(StaffMemberView member) {
        return new StaffUserDto(member.id(), member.email(), member.role(), member.active(),
                member.createdAt(), toDentist(member.dentist()), member.status());
    }

    private static DentistDto toDentist(DentistProfile profile) {
        if (profile == null) {
            return null;
        }
        return new DentistDto(profile.firstName(), profile.lastName(), profile.phone(),
                profile.specialty(), profile.licenseNumber(), profile.publicInfo(), profile.photoUrl());
    }
}
