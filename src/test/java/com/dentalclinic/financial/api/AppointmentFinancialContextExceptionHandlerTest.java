package com.dentalclinic.financial.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.dentalclinic.financial.application.AppointmentFinancialContextService;
import com.dentalclinic.financial.domain.FinancialContextUnavailableException;
import com.dentalclinic.identity.domain.Role;
import com.dentalclinic.identity.infrastructure.security.AuthenticatedUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Contrato HTTP de errores del contexto financiero (UX-05B.1, Etapa 2; auditoria I-03).
 *
 * <p>El GET comparte modulo con las escrituras, pero <strong>no</strong> su advice: un fallo
 * tecnico debe ser un {@code 500} generico, nunca un {@code 409} con instrucciones de reintento con
 * {@code Idempotency-Key}. Este test ejercita el controlador y el advice reales con un servicio
 * simulado, sin crear un segundo contexto de Spring ni tocar la base.
 */
class AppointmentFinancialContextExceptionHandlerTest {

    private AppointmentFinancialContextService contexts;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        contexts = mock(AppointmentFinancialContextService.class);
        AuthenticatedUser owner = new AuthenticatedUser(1L, Role.OWNER);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(owner, null, List.of()));
        mvc = MockMvcBuilders.standaloneSetup(new AppointmentFinancialContextController(contexts))
                .setControllerAdvice(new AppointmentFinancialContextExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(
                        new ObjectMapper().registerModule(new JavaTimeModule())))
                .build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void unFalloDePersistenciaNoSeConvierteEnUn409DeEscritura() throws Exception {
        when(contexts.contextFor(anyLong(), any()))
                .thenThrow(new DataIntegrityViolationException("detalle interno que no debe salir"));

        String body = mvc.perform(get("/api/appointments/1/financial-context"))
                .andExpect(status().isInternalServerError())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.code").doesNotExist())
                .andExpect(jsonPath("$.charges").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("FINANCIAL_CONTEXT_UNAVAILABLE", "Idempotency-Key",
                "clave", "detalle interno", "SQL", "stackTrace", "org.springframework");
    }

    @Test
    void unaInconsistenciaVerificableSigueSiendoUn409GenericoSinDatos() throws Exception {
        when(contexts.contextFor(anyLong(), any())).thenThrow(new FinancialContextUnavailableException());

        mvc.perform(get("/api/appointments/1/financial-context"))
                .andExpect(status().isConflict())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.code").value("FINANCIAL_CONTEXT_UNAVAILABLE"))
                .andExpect(jsonPath("$.charges").doesNotExist())
                .andExpect(jsonPath("$.charges[0]").doesNotExist());
    }

    @Test
    void unFormatoInvalidoEs400YNoUnErrorTecnico() throws Exception {
        mvc.perform(get("/api/appointments/no-es-un-id/financial-context"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").doesNotExist());
    }

    @Test
    void elAdviceFinancieroDeEscrituraYaNoSeAplicaAEsteControlador() {
        RestControllerAdvice writing = FinancialExceptionHandler.class.getAnnotation(RestControllerAdvice.class);
        RestControllerAdvice reading =
                AppointmentFinancialContextExceptionHandler.class.getAnnotation(RestControllerAdvice.class);

        assertThat(writing.assignableTypes()).doesNotContain(AppointmentFinancialContextController.class);
        assertThat(reading.assignableTypes()).containsExactly(AppointmentFinancialContextController.class,
                CollectionQueueController.class);
        assertThat(writing.assignableTypes()).doesNotContain(CollectionQueueController.class);
    }
}
