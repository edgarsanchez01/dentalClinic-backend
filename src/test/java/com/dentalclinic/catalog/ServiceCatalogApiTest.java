package com.dentalclinic.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.dentalclinic.catalog.application.ServiceCatalogService;
import com.dentalclinic.catalog.application.ServiceCatalogService.ServiceData;
import com.dentalclinic.catalog.infrastructure.repository.ServiceCatalogRepository;
import com.dentalclinic.identity.IdentityIntegrationTestSupport;
import com.dentalclinic.identity.TestTime;
import com.dentalclinic.identity.domain.Role;
import com.dentalclinic.identity.infrastructure.security.AuthenticatedUser;
import com.fasterxml.jackson.databind.ObjectMapper;

/** MockMvc + cadena real de seguridad + PostgreSQL efímero con DDL V001..V016 y Hibernate validate. */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:tc:postgresql:16:///dentalclinic_catalog",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=file:../db/schema/V001__baseline.sql,"
                + "file:../db/schema/V002__identity_access.sql,file:../db/schema/V003__clinic_onboarding.sql,"
                + "file:../db/schema/V004__patients_clinical.sql,file:../db/schema/V005__scheduling.sql,"
                + "file:../db/schema/V006__appointments.sql,file:../db/schema/V007__service_catalog.sql,"
                + "file:../db/schema/V008__appointment_service_snapshot.sql,"
                + "file:../db/schema/V009__treatments.sql,file:../db/schema/V010__odontogram.sql,"
                + "file:../db/schema/V011__odontogram_surfaces.sql,"
                + "file:../db/schema/V012__treatment_evolution.sql,"
                + "file:../db/schema/V013__clinical_appointment_context.sql,"
                + "file:../db/schema/V014__clinical_corrections.sql,"
                + "file:../db/schema/V015__odontogram_history_context.sql,"
                + "file:../db/schema/V016__financial_module.sql,"
                + "file:../db/schema/V017__patient_feedback.sql,"
                + "file:../db/schema/V018__identity_email_outbox.sql,"
                + "file:../db/schema/V019__patient_medical_intake.sql,"
                + "file:../db/schema/V020__appointment_checkout.sql"
})
class ServiceCatalogApiTest extends IdentityIntegrationTestSupport {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired ServiceCatalogService service;
    @Autowired ServiceCatalogRepository repository;
    @Autowired JdbcTemplate jdbc;

    private static final String BODY = """
            {"name":"  Limpieza dental  ","description":"Profilaxis", "referencePrice":75.1234,"durationMinutes":35}
            """;

    @BeforeEach
    void cleanCatalog() {
        TestTime.reset();
        repository.deleteAll();
    }

    private RequestPostProcessor as(Role role) {
        return authentication(new UsernamePasswordAuthenticationToken(new AuthenticatedUser(42L, role), null, List.of()));
    }

    private Long item() {
        return service.create(new ServiceData("Limpieza", "Referencia", new BigDecimal("75.1234"), 35)).getId();
    }

    @Test
    void owner_creates_updates_deactivates_and_reactivates_without_deleting() throws Exception {
        var result = mvc.perform(post("/api/services").with(as(Role.OWNER))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.name").value("Limpieza dental"))
                .andExpect(jsonPath("$.active").value(true)).andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andReturn();
        Long id = mapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
        assertThat(result.getResponse().getHeader("Location")).isEqualTo("/api/services/" + id);
        assertThat(jdbc.queryForObject("select reference_price from service_catalog_item where id=?", BigDecimal.class, id))
                .isEqualByComparingTo("75.1234");
        TestTime.advance(java.time.Duration.ofMinutes(1));
        mvc.perform(put("/api/services/{id}", id).with(as(Role.OWNER)).contentType(MediaType.APPLICATION_JSON)
                        .content(BODY.replace("75.1234", "90.0001").replace("35", "45")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.durationMinutes").value(45))
                .andExpect(jsonPath("$.updatedAt").isNotEmpty());
        for (boolean active : new boolean[]{false, true}) {
            mvc.perform(patch("/api/services/{id}/active", id).with(as(Role.OWNER))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"active\":" + active + "}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(active));
            assertThat(repository.findById(id)).isPresent();
        }
        mvc.perform(delete("/api/services/{id}", id).with(as(Role.OWNER))).andExpect(status().isMethodNotAllowed());
        assertThat(repository.count()).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"OWNER", "DENTIST", "RECEPTIONIST"})
    void staff_reads_list_and_detail_including_inactive(Role role) throws Exception {
        Long id = item();
        service.setActive(id, false);
        mvc.perform(get("/api/services").with(as(role))).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/api/services").param("active", "true").with(as(role)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/services").param("active", "false").with(as(role)))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(id));
        mvc.perform(get("/api/services/{id}", id).with(as(role))).andExpect(status().isOk());
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"DENTIST", "RECEPTIONIST", "PATIENT"})
    void other_roles_cannot_administer(Role role) throws Exception {
        Long id = item();
        mvc.perform(post("/api/services").with(as(role)).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/services/{id}", id).with(as(role)).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
        mvc.perform(patch("/api/services/{id}/active", id).with(as(role)).contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}"))
                .andExpect(status().isForbidden());
        assertThat(service.get(id).isActive()).isTrue();
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void patient_and_anonymous_cannot_read_administrative_catalog() throws Exception {
        Long id = item();
        mvc.perform(get("/api/services").with(as(Role.PATIENT))).andExpect(status().isForbidden());
        mvc.perform(get("/api/services/{id}", id).with(as(Role.PATIENT))).andExpect(status().isForbidden());
        mvc.perform(get("/api/services")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/services").contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isUnauthorized());
    }

    @Test
    void public_only_publishes_active_items_with_explicit_dto() throws Exception {
        Long id = item();
        Long hidden = service.create(new ServiceData("Inactivo", null, BigDecimal.ZERO, 15)).getId();
        service.setActive(hidden, false);
        mvc.perform(get("/api/public/services").param("active", "false"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(id)).andExpect(jsonPath("$[0].active").doesNotExist())
                .andExpect(jsonPath("$[0].createdAt").doesNotExist()).andExpect(jsonPath("$[0].updatedAt").doesNotExist());
        mvc.perform(get("/api/public/services/{id}", id)).andExpect(status().isOk());
        mvc.perform(get("/api/public/services/{id}", id).with(as(Role.PATIENT))).andExpect(status().isOk());
        mvc.perform(get("/api/public/services/{id}", hidden)).andExpect(status().isNotFound());
        mvc.perform(get("/api/public/services/{id}", Long.MAX_VALUE)).andExpect(status().isNotFound());
        mvc.perform(post("/api/public/services").contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/public/not-implemented").with(as(Role.OWNER))).andExpect(status().isForbidden());
        mvc.perform(get("/api/public/services/1/internal").with(as(Role.OWNER))).andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{}", "{\"name\":\"  \",\"referencePrice\":10,\"durationMinutes\":30}",
        "{\"name\":\"X\",\"referencePrice\":-1,\"durationMinutes\":30}",
        "{\"name\":\"X\",\"referencePrice\":1.00001,\"durationMinutes\":30}",
        "{\"name\":\"X\",\"referencePrice\":1000000000000000,\"durationMinutes\":30}",
        "{\"name\":\"X\",\"referencePrice\":10,\"durationMinutes\":0}",
        "{\"name\":\"X\",\"referencePrice\":10,\"durationMinutes\":-1}"
    })
    void invalid_data_returns_400_and_does_not_persist(String body) throws Exception {
        mvc.perform(post("/api/services").with(as(Role.OWNER)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors").isNotEmpty());
        assertThat(repository.count()).isZero();
    }

    @Test
    void length_limits_malformed_input_and_missing_resources_use_uniform_errors() throws Exception {
        for (String body : List.of(BODY.replace("  Limpieza dental  ", "X".repeat(201)),
                BODY.replace("Profilaxis", "X".repeat(4001)), "{invalid}",
                BODY.replace("\"durationMinutes\":35", "\"durationMinutes\":35.5"),
                BODY.replace("\"durationMinutes\":35", "\"durationMinutes\":2147483648"))) {
            mvc.perform(post("/api/services").with(as(Role.OWNER)).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.path").value("/api/services"));
        }
        mvc.perform(get("/api/services/not-a-number").with(as(Role.OWNER))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/services/{id}", Long.MAX_VALUE).with(as(Role.OWNER))).andExpect(status().isNotFound());
        mvc.perform(put("/api/services/{id}", Long.MAX_VALUE).with(as(Role.OWNER)).contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isNotFound());
        mvc.perform(patch("/api/services/{id}/active", Long.MAX_VALUE).with(as(Role.OWNER)).contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}")).andExpect(status().isNotFound());
        mvc.perform(patch("/api/services/{id}/active", item()).with(as(Role.OWNER)).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void database_constraints_protect_direct_writes_and_catalog_starts_empty() {
        assertThat(repository.count()).isZero();
        for (Object[] values : List.of(new Object[]{" ", BigDecimal.ONE, 30}, new Object[]{"\t\n", BigDecimal.ONE, 30},
                new Object[]{"X", new BigDecimal("-1"), 30}, new Object[]{"X", BigDecimal.ONE, 0})) {
            assertThatThrownBy(() -> jdbc.update("insert into service_catalog_item(name,reference_price,duration_minutes) values(?,?,?)", values))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
        Long id = item();
        assertThat(jdbc.queryForObject("select duration_minutes from service_catalog_item where id=?", Integer.class, id)).isEqualTo(35);
        assertThat(jdbc.queryForObject("select reference_price from service_catalog_item where id=?", BigDecimal.class, id))
                .isEqualByComparingTo("75.1234");
    }
}
