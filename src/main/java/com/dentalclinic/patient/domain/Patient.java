package com.dentalclinic.patient.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

/**
 * Paciente: perfil <strong>administrativo</strong> (Architecture.md §15.3, Fase 4).
 *
 * <p>No duplica credenciales ni rol: esos viven en {@code User}. El vínculo entre ambos es
 * 0..1 en los dos sentidos (P-08 vinculante):
 *
 * <ul>
 *   <li>{@code userId} puede ser {@code null}: la clínica registra pacientes que aún no tienen
 *       cuenta (por ejemplo, un paciente dado de alta en recepción).</li>
 *   <li>{@code userId} es único cuando existe: un {@code User} no puede apuntar a dos
 *       {@code Patient}.</li>
 * </ul>
 *
 * <p>{@code email} es un dato administrativo de contacto, <strong>opcional y no único</strong>.
 * No es la identidad de la cuenta: esa es {@code User.email} (obligatoria y única, AD-20). El
 * registro público crea la cuenta con su email y deja {@code Patient.email} vacío.
 *
 * <p>{@code active} es la vigencia del paciente gestionada por administración (RF-PA-03), no el
 * estado de la cuenta. Es independiente de {@code User.active}: desactivar a un paciente no
 * desactiva su cuenta, y desactivar la cuenta no cambia su vigencia como paciente.
 *
 * <p>La asociación a un {@code User} <strong>nunca se infiere</strong> por email, nombre ni
 * teléfono (AD-22, P-08): solo puede establecerse en la creación desde la ruta autorizada
 * (registro público) o por un mecanismo explícito futuro. Hoy no existe endpoint para vincular un
 * {@code Patient} preexistente a una cuenta; ver la brecha documentada en PRD §37.
 */
@Entity
@Table(name = "patient")
public class Patient {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", unique = true)
    private Long userId;

    @Column(name = "first_name", nullable = false, length = 100)
    private String firstName;

    @Column(name = "last_name", nullable = false, length = 100)
    private String lastName;

    @Column(name = "email", length = 320)
    private String email;

    @Column(name = "phone", length = 50)
    private String phone;

    @Column(name = "birth_date")
    private LocalDate birthDate;

    @Column(name = "can_provide_phone")
    private Boolean canProvidePhone;
    @Column(name = "emergency_contact_name", length = 200)
    private String emergencyContactName;
    @Column(name = "emergency_contact_relationship", length = 100)
    private String emergencyContactRelationship;
    @Column(name = "emergency_contact_phone", length = 50)
    private String emergencyContactPhone;
    @Column(name = "responsible_adult_name", length = 200)
    private String responsibleAdultName;
    @Enumerated(EnumType.STRING)
    @Column(name = "responsible_adult_relationship", length = 20)
    private IntakePersonalData.ResponsibleRelationship responsibleAdultRelationship;
    @Column(name = "responsible_adult_phone", length = 50)
    private String responsibleAdultPhone;

    @Enumerated(EnumType.STRING)
    @Column(name = "medical_onboarding_requirement", nullable = false, length = 20)
    private MedicalOnboardingRequirement medicalOnboardingRequirement = MedicalOnboardingRequirement.EXEMPT_EXISTING;
    @Enumerated(EnumType.STRING)
    @Column(name = "medical_onboarding_progress", nullable = false, length = 20)
    private MedicalOnboardingProgress medicalOnboardingProgress = MedicalOnboardingProgress.NOT_STARTED;
    @Column(name = "intake_version", nullable = false)
    private long intakeVersion;

    @Column(name = "administrative_notes")
    private String administrativeNotes;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false,
            columnDefinition = "TIMESTAMPTZ DEFAULT now()")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    protected Patient() {
        // Para JPA.
    }

    private Patient(Long userId, String firstName, String lastName, String email, String phone,
                    LocalDate birthDate, String administrativeNotes) {
        this.userId = userId;
        this.firstName = Objects.requireNonNull(firstName, "firstName es obligatorio");
        this.lastName = Objects.requireNonNull(lastName, "lastName es obligatorio");
        this.email = email;
        this.phone = phone;
        this.birthDate = birthDate;
        this.administrativeNotes = administrativeNotes;
        this.active = true;
    }

    /** Paciente sin cuenta de usuario (alta administrativa). */
    public static Patient create(String firstName, String lastName, String email, String phone,
                                 LocalDate birthDate, String administrativeNotes) {
        return new Patient(null, firstName, lastName, email, phone, birthDate, administrativeNotes);
    }

    /**
     * Paciente creado junto a su cuenta en el registro público (RF-IA-05/06). El {@code userId}
     * procede de la cuenta recién creada en la <strong>misma transacción</strong>; no se infiere.
     */
    public static Patient createForUser(Long userId, String firstName, String lastName, String email,
                                        String phone, LocalDate birthDate, String administrativeNotes) {
        return new Patient(Objects.requireNonNull(userId, "userId es obligatorio para un paciente con cuenta"),
                firstName, lastName, email, phone, birthDate, administrativeNotes);
    }

    /**
     * Actualiza los datos administrativos. {@code null} limpia los campos opcionales. El estado de
     * vigencia solo cambia si se solicita explícitamente ({@code active} no nulo).
     */
    public void update(String firstName, String lastName, String email, String phone,
                       LocalDate birthDate, String administrativeNotes, Boolean active, Instant now) {
        this.firstName = Objects.requireNonNull(firstName, "firstName es obligatorio");
        this.lastName = Objects.requireNonNull(lastName, "lastName es obligatorio");
        this.email = email;
        this.phone = phone;
        this.birthDate = birthDate;
        this.administrativeNotes = administrativeNotes;
        if (active != null) {
            this.active = active;
        }
        this.updatedAt = now;
        this.intakeVersion++;
    }

    public void requireMedicalOnboarding() {
        medicalOnboardingRequirement = MedicalOnboardingRequirement.REQUIRED;
    }

    public void saveIntakePersonal(IntakePersonalData data, Instant now) {
        firstName = data.firstName();
        lastName = data.lastName();
        birthDate = data.birthDate();
        phone = data.phone();
        canProvidePhone = data.canProvidePhone();
        emergencyContactName = data.emergencyContactName();
        emergencyContactRelationship = data.emergencyContactRelationship();
        emergencyContactPhone = data.emergencyContactPhone();
        responsibleAdultName = data.responsibleAdultName();
        responsibleAdultRelationship = data.responsibleAdultRelationship();
        responsibleAdultPhone = data.responsibleAdultPhone();
        advanceIntake(false, now);
    }

    public IntakePersonalData intakePersonal() {
        return new IntakePersonalData(firstName, lastName, birthDate, phone, canProvidePhone,
                emergencyContactName, emergencyContactRelationship, emergencyContactPhone,
                responsibleAdultName, responsibleAdultRelationship, responsibleAdultPhone);
    }

    public void advanceIntake(boolean completed, Instant now) {
        if (completed) medicalOnboardingProgress = MedicalOnboardingProgress.COMPLETED;
        else if (medicalOnboardingProgress == MedicalOnboardingProgress.NOT_STARTED)
            medicalOnboardingProgress = MedicalOnboardingProgress.IN_PROGRESS;
        intakeVersion++;
        updatedAt = now;
    }

    public MedicalOnboardingRequirement getMedicalOnboardingRequirement() { return medicalOnboardingRequirement; }
    public MedicalOnboardingProgress getMedicalOnboardingProgress() { return medicalOnboardingProgress; }
    public long getIntakeVersion() { return intakeVersion; }
    public boolean isMedicalOnboardingPending() {
        return medicalOnboardingRequirement == MedicalOnboardingRequirement.REQUIRED
                && medicalOnboardingProgress != MedicalOnboardingProgress.COMPLETED;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public String getFirstName() {
        return firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public String getEmail() {
        return email;
    }

    public String getPhone() {
        return phone;
    }

    public LocalDate getBirthDate() {
        return birthDate;
    }

    public String getAdministrativeNotes() {
        return administrativeNotes;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
