package com.dentalclinic.appointment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import com.dentalclinic.appointment.api.dto.AppointmentResponse;
import com.dentalclinic.appointment.api.dto.CreateAppointmentRequest;
import com.dentalclinic.appointment.domain.AppointmentStatus;
import com.dentalclinic.appointment.domain.HttpIdempotency;
import com.dentalclinic.appointment.infrastructure.repository.HttpIdempotencyRepository;
import com.dentalclinic.identity.domain.Role;
import com.dentalclinic.identity.infrastructure.security.AuthenticatedUser;
import com.dentalclinic.scheduling.domain.ScheduleConflictException;
import com.dentalclinic.scheduling.domain.exception.InvalidScheduleException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

class AppointmentCreateCoordinatorTest {

    private final AuthenticatedUser actor = new AuthenticatedUser(1L, Role.OWNER);
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private AppointmentService service;
    private HttpIdempotencyRepository repository;
    private AppointmentCreateCoordinator coordinator;
    private CreateAppointmentRequest request;
    private AppointmentResponse response;
    private HttpIdempotency stored;

    @BeforeEach
    void setUp() {
        service = mock(AppointmentService.class);
        repository = mock(HttpIdempotencyRepository.class);
        coordinator = new AppointmentCreateCoordinator(service, repository, mapper);
        Instant start = Instant.parse("2026-10-13T09:00:00Z");
        Instant end = start.plusSeconds(3600);
        request = new CreateAppointmentRequest(2L, 3L, start, end, "Consulta", null, 5L);
        response = new AppointmentResponse(4L, 2L, "Paciente", "Uno", null,
                3L, "Dentista", "Uno", start, end, 60, AppointmentStatus.SCHEDULED,
                1, 1L, "Consulta", null, null, start, start, null);
        when(repository.findByIdentity(1L, "CREATE_APPOINTMENT", "key"))
                .thenAnswer(invocation -> Optional.ofNullable(stored));
        when(service.create(any(), eq(actor), eq("key"), any())).thenAnswer(invocation -> {
            Map<String, Object> body = mapper.convertValue(response, new TypeReference<>() { });
            stored = HttpIdempotency.create(1L, "CREATE_APPOINTMENT", "key",
                    invocation.getArgument(3), 201, body);
            return new AppointmentService.CreatedAppointment(response);
        });
    }

    @Test
    void creaYRepiteLaRespuestaSinDuplicarLaOperacion() {
        assertThat(coordinator.create(actor, request, "key")).isEqualTo(response);
        assertThat(coordinator.create(actor, request, "key")).isEqualTo(response);
        verify(service).create(any(), eq(actor), eq("key"), any());
        verifyNoMoreInteractions(service);
    }

    @Test
    void rechazaOtroPayloadConLaMismaClaveYConservaElReplay() {
        coordinator.create(actor, request, "key");
        var different = new CreateAppointmentRequest(2L, 3L, request.startAt(),
                request.endAt(), "Otro motivo", null, 5L);
        assertThatThrownBy(() -> coordinator.create(actor, different, "key"))
                .isInstanceOf(ScheduleConflictException.class)
                .hasMessage("La cabecera Idempotency-Key ya fue usada por otra petición con distinto contenido.");
        assertThat(coordinator.create(actor, request, "key")).isEqualTo(response);
        verify(service).create(any(), eq(actor), eq("key"), any());
        verifyNoMoreInteractions(service);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void exigeUnaClaveNoVacia(String key) {
        assertThatThrownBy(() -> coordinator.create(actor, request, key))
                .isInstanceOf(InvalidScheduleException.class)
                .hasMessage("La cabecera Idempotency-Key es obligatoria para crear citas.");
        verifyNoInteractions(service, repository);
    }
}
