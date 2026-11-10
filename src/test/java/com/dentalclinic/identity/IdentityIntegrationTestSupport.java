package com.dentalclinic.identity;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import com.dentalclinic.appointment.infrastructure.repository.AppointmentEventRepository;
import com.dentalclinic.appointment.infrastructure.repository.AppointmentRepository;
import com.dentalclinic.appointment.infrastructure.repository.CommunicationIntentionRepository;
import com.dentalclinic.appointment.infrastructure.repository.CommunicationRepository;
import com.dentalclinic.appointment.infrastructure.repository.HttpIdempotencyRepository;
import com.dentalclinic.appointment.infrastructure.repository.NotificationRepository;
import com.dentalclinic.clinic.infrastructure.repository.ClinicRepository;
import com.dentalclinic.clinic.infrastructure.repository.DentistRepository;
import com.dentalclinic.clinical.infrastructure.repository.ClinicalNoteRepository;
import com.dentalclinic.clinical.infrastructure.repository.ClinicalRecordRepository;
import com.dentalclinic.clinical.infrastructure.repository.TreatmentEvolutionRepository;
import com.dentalclinic.clinical.infrastructure.repository.TreatmentRepository;
import com.dentalclinic.odontogram.infrastructure.repository.ToothHistoryRepository;
import com.dentalclinic.odontogram.infrastructure.repository.ToothRecordRepository;
import com.dentalclinic.odontogram.infrastructure.repository.ToothSurfaceHistoryRepository;
import com.dentalclinic.odontogram.infrastructure.repository.ToothSurfaceRecordRepository;
import com.dentalclinic.odontogram.infrastructure.repository.TreatmentToothRepository;
import com.dentalclinic.patient.infrastructure.repository.PatientRepository;
import com.dentalclinic.scheduling.infrastructure.repository.ClinicBreakRepository;
import com.dentalclinic.scheduling.infrastructure.repository.ClinicScheduleExceptionRepository;
import com.dentalclinic.scheduling.infrastructure.repository.ClinicScheduleRepository;
import com.dentalclinic.scheduling.infrastructure.repository.DentistBreakRepository;
import com.dentalclinic.scheduling.infrastructure.repository.DentistScheduleExceptionRepository;
import com.dentalclinic.scheduling.infrastructure.repository.DentistScheduleRepository;
import com.dentalclinic.scheduling.infrastructure.repository.DentistTimeOffRepository;

/**
 * Base común de los tests de integración de Fase 2.
 *
 * <p>Levanta el contexto real de Spring Boot con el perfil {@code test} (PostgreSQL efímero vía
 * Testcontainers, ddl-auto=create-drop por entidades) e inyecta el reloj mutable.
 * <strong>No</strong> extiende {@code PostgresTestSupport} a propósito: ese soporte es de paquete
 * {@code foundation} y su contenedor se comparte con las guardas de fixtures; los tests de hierba
 * usan su propio contenedor a través de la URL {@code jdbc:tc:} del perfil test.
 *
 * <p>Desde Fase 4, la base limpia antes de cada test las tablas clínicas y de pacientes. Es
 * necesario porque comparten contenedor entre clases de test y tienen claves foráneas hacia
 * {@code users}: si un test de pacientes deja filas, los {@code cleanSlate} heredados que borran
 * usuarios fallarían por integridad referencial. El orden importa: primero las tablas hijas.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestTimeConfig.class)
public abstract class IdentityIntegrationTestSupport {

    @Autowired
    private ClinicalNoteRepository clinicalNoteRepository;
    @Autowired
    private ClinicalRecordRepository clinicalRecordRepository;
    @Autowired
    private PatientRepository patientRepository;
    @Autowired
    private CommunicationIntentionRepository communicationIntentionRepository;
    @Autowired
    private NotificationRepository notificationRepository;
    @Autowired
    private CommunicationRepository communicationRepository;
    @Autowired
    private AppointmentEventRepository appointmentEventRepository;
    @Autowired
    private AppointmentRepository appointmentRepository;
    @Autowired
    private HttpIdempotencyRepository httpIdempotencyRepository;
    @Autowired
    private DentistTimeOffRepository dentistTimeOffRepository;
    @Autowired
    private DentistScheduleExceptionRepository dentistScheduleExceptionRepository;
    @Autowired
    private DentistBreakRepository dentistBreakRepository;
    @Autowired
    private DentistScheduleRepository dentistScheduleRepository;
    @Autowired
    private ClinicScheduleExceptionRepository clinicScheduleExceptionRepository;
    @Autowired
    private ClinicBreakRepository clinicBreakRepository;
    @Autowired
    private ClinicScheduleRepository clinicScheduleRepository;
    @Autowired
    private DentistRepository dentistRepository;
    @Autowired
    private ClinicRepository clinicRepository;
    @Autowired
    private TreatmentRepository treatmentRepository;
    @Autowired
    private TreatmentEvolutionRepository treatmentEvolutionRepository;
    @Autowired
    private ToothHistoryRepository toothHistoryRepository;
    @Autowired
    private ToothSurfaceHistoryRepository toothSurfaceHistoryRepository;
    @Autowired
    private ToothSurfaceRecordRepository toothSurfaceRecordRepository;
    @Autowired
    private TreatmentToothRepository treatmentToothRepository;
    @Autowired
    private ToothRecordRepository toothRecordRepository;

    @Autowired
    private com.dentalclinic.appointment.infrastructure.repository.AppointmentOccurrenceSnapshotRepository occurrenceSnapshots;

    @Autowired private org.springframework.jdbc.core.JdbcTemplate cleanupJdbc;
    @Autowired private org.springframework.core.env.Environment testEnvironment;

    /**
     * Limpia antes de cada test las tablas que referencian a {@code patient}, {@code dentist} o
     * {@code clinic}: clínicas/pacientes y, desde Fase 5, todo el Scheduling; desde Fase 6, las
     * tablas de citas y comunicaciones. El orden hijo→padre evita violaciones de integridad
     * referencial cuando los {@code cleanSlate} heredados borran usuarios o dentistas.
     */
    @BeforeEach
    void cleanSharedDependentTables() {
        // Guard before fixture writes: never use a datasource configured for an operator's database.
        org.assertj.core.api.Assertions.assertThat(testEnvironment.getProperty("spring.datasource.url"))
                .startsWith("jdbc:tc:postgresql:");
        cleanupJdbc.update("DELETE FROM patient_intake_declaration");
        cleanupJdbc.update("DELETE FROM patient_intake_privacy");
        cleanupJdbc.update("DELETE FROM patient_intake_draft");
        cleanupJdbc.update("DELETE FROM tooth_surface_notes_history");
        cleanupJdbc.update("DELETE FROM patient_feedback");
        // Outbox de identidad (F8B.2): referencia a users, se limpia antes de cualquier borrado de
        // usuarios heredado para no violar la FK restrictiva.
        cleanupJdbc.update("DELETE FROM identity_email_outbox");
        cleanupJdbc.update("DELETE FROM tooth_notes_history");
        cleanupJdbc.update("DELETE FROM tooth_treatment_history");
        toothSurfaceHistoryRepository.deleteAll();
        toothSurfaceRecordRepository.deleteAll();
        toothHistoryRepository.deleteAll();
        treatmentToothRepository.deleteAll();
        toothRecordRepository.deleteAll();
        treatmentEvolutionRepository.deleteCorrections();
        treatmentEvolutionRepository.deleteAll();
        treatmentRepository.deleteAll();
        clinicalNoteRepository.deleteCorrections();
        clinicalNoteRepository.deleteAll();
        occurrenceSnapshots.deleteAll();
        communicationIntentionRepository.deleteAll();
        notificationRepository.deleteAll();
        communicationRepository.deleteAll();
        appointmentEventRepository.deleteAll();
        appointmentRepository.deleteAll();
        httpIdempotencyRepository.deleteAll();
        dentistTimeOffRepository.deleteAll();
        dentistScheduleExceptionRepository.deleteAll();
        dentistBreakRepository.deleteAll();
        dentistScheduleRepository.deleteAll();
        clinicScheduleExceptionRepository.deleteAll();
        clinicBreakRepository.deleteAll();
        clinicScheduleRepository.deleteAll();
        clinicalNoteRepository.deleteAll();
        clinicalRecordRepository.deleteAll();
        patientRepository.deleteAll();
        dentistRepository.deleteAll();
        clinicRepository.deleteAll();
    }
}
