package com.dentalclinic.odontogram.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import com.dentalclinic.identity.api.RestExceptionHandler;
import com.dentalclinic.identity.domain.Role;
import com.dentalclinic.identity.domain.exception.ForbiddenOperationException;
import com.dentalclinic.identity.domain.exception.NotFoundException;
import com.dentalclinic.identity.infrastructure.repository.UserRepository;
import com.dentalclinic.identity.infrastructure.security.*;
import com.dentalclinic.odontogram.application.ReceptionDentalSummaryService;
import com.dentalclinic.odontogram.domain.ToothCondition;
import com.dentalclinic.odontogram.domain.ToothSurface;
import com.dentalclinic.odontogram.infrastructure.repository.ReceptionDentalSummaryRepository;
import com.dentalclinic.patient.application.PatientService;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Real MVC + production security chain/service. Only persistence is mocked; NO database/Docker. */
@SpringJUnitWebConfig(ReceptionDentalSummarySecurityTest.Config.class)
class ReceptionDentalSummarySecurityTest {
    static final String PATH = "/api/patients/42/odontogram/reception-summary";
    @Autowired WebApplicationContext context;
    @Autowired PatientService patients;
    @Autowired ReceptionDentalSummaryRepository repository;
    @Autowired ReceptionDentalSummaryService service;
    MockMvc mvc;

    @BeforeEach void setup() {
        reset(patients, repository);
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test void anonymousCannotRead() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        verifyNoInteractions(patients, repository);
    }

    @ParameterizedTest @EnumSource(value = Role.class, names = {"OWNER", "DENTIST", "PATIENT"})
    void otherRolesCannotUseReceptionRoute(Role role) throws Exception {
        mvc.perform(get(PATH).with(actor(role))).andExpect(status().isForbidden());
        verifyNoInteractions(patients, repository);
    }

    @ParameterizedTest @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
    void receptionCannotWriteEvenToSummary(String method) throws Exception {
        mvc.perform(request(HttpMethod.valueOf(method), PATH).with(actor(Role.RECEPTIONIST))
                .contentType("application/json").content("{\"condition\":\"HEALTHY\",\"notes\":\"private\"}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(patients, repository);
    }

    @ParameterizedTest @ValueSource(strings = {"", "/16", "/16/surfaces", "/history", "/at", "/reception-summary/extra"})
    void receptionNeverGainsClinicalNamespace(String suffix) throws Exception {
        mvc.perform(get("/api/patients/42/odontogram" + suffix).with(actor(Role.RECEPTIONIST)))
                .andExpect(status().isForbidden());
        verifyNoInteractions(patients, repository);
    }

    @ParameterizedTest @ValueSource(strings = {"notes", "treatments", "medical-intake/declarations"})
    void receptionCannotReadOtherClinicalData(String suffix) throws Exception {
        mvc.perform(get("/api/patients/42/clinical/" + suffix).with(actor(Role.RECEPTIONIST)))
                .andExpect(status().isForbidden());
        verifyNoInteractions(patients, repository);
    }

    @Test void allowlistAndPatientScopeCannotBeExpandedByQueryParameters() throws Exception {
        var row = mock(ReceptionDentalSummaryRepository.SummaryRow.class);
        when(row.getToothFdi()).thenReturn(16);
        when(row.getCondition()).thenReturn(ToothCondition.CARIES);
        when(row.getSurface()).thenReturn(ToothSurface.OCCLUSAL);
        when(row.getSurfaceCondition()).thenReturn(ToothCondition.RESTORED);
        when(repository.findCurrentSummary(42L)).thenReturn(List.of(row));
        var result = mvc.perform(get(PATH).param("patientId", "99").param("includeNotes", "true")
                .param("history", "true").with(actor(Role.RECEPTIONIST)))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$[0].toothFdi").value(16))
                .andExpect(jsonPath("$[0].surfaces[0].condition").value("RESTORED"))
                .andReturn().getResponse();
        var json = new ObjectMapper().readTree(result.getContentAsString());
        assertThat(json.get(0).properties()).extracting(java.util.Map.Entry::getKey)
                .containsExactlyInAnyOrder("toothFdi", "dentition", "condition", "surfaces");
        assertThat(json.get(0).get("surfaces").get(0).properties()).extracting(java.util.Map.Entry::getKey)
                .containsExactlyInAnyOrder("surface", "condition");
        verify(patients).get(42L);
        verify(repository).findCurrentSummary(42L);
        verifyNoMoreInteractions(patients, repository);
    }

    @Test void missingPatientDoesNotQueryDentalData() throws Exception {
        when(patients.get(42L)).thenThrow(new NotFoundException());
        mvc.perform(get(PATH).with(actor(Role.RECEPTIONIST))).andExpect(status().isNotFound());
        verifyNoInteractions(repository);
    }

    @ParameterizedTest @EnumSource(value = Role.class, names = {"OWNER", "DENTIST", "PATIENT"})
    void serviceAlsoDeniesWrongRole(Role role) {
        assertThatThrownBy(() -> service.read(42L, new AuthenticatedUser(7L, role)))
                .isInstanceOf(ForbiddenOperationException.class);
        verifyNoInteractions(patients, repository);
    }

    @Test void invalidPatientAndNullActorFailClosed() {
        assertThatThrownBy(() -> service.read(42L, null)).isInstanceOf(ForbiddenOperationException.class);
        assertThatThrownBy(() -> service.read(-1L, new AuthenticatedUser(7L, Role.RECEPTIONIST)))
                .isInstanceOf(NotFoundException.class);
        verifyNoInteractions(patients, repository);
    }

    @Test void emptyStateIsNotSynthesizedAsHealthy() throws Exception {
        mvc.perform(get(PATH).with(actor(Role.RECEPTIONIST))).andExpect(status().isOk()).andExpect(content().json("[]"));
    }

    static org.springframework.test.web.servlet.request.RequestPostProcessor actor(Role role) {
        return authentication(new UsernamePasswordAuthenticationToken(new AuthenticatedUser(7L, role), null, List.of()));
    }

    @Configuration @EnableWebMvc
    @Import({SecurityConfig.class, EndpointAccessPolicy.class, EndpointAccessFilter.class,
        JsonAuthenticationEntryPoint.class, JsonAccessDeniedHandler.class, CsrfCookieFilter.class,
        RestExceptionHandler.class, ReceptionDentalSummaryController.class, ReceptionDentalSummaryService.class})
    static class Config {
        @Bean ObjectMapper mapper() { return new ObjectMapper().findAndRegisterModules(); }
        @Bean PatientService patients() { return mock(PatientService.class); }
        @Bean ReceptionDentalSummaryRepository repository() { return mock(ReceptionDentalSummaryRepository.class); }
        @Bean JwtAuthenticationFilter jwt() { return new JwtAuthenticationFilter(mock(JwtTokenService.class), mock(UserRepository.class)); }
        @Bean SecurityProperties securityProperties() {
            return new SecurityProperties(null, null, null, null,
                    new SecurityProperties.RefreshCookie("test-refresh", false, "Lax", "/api/auth"),
                    new SecurityProperties.Cors(List.of("http://127.0.0.1:5197")));
        }
    }
}
