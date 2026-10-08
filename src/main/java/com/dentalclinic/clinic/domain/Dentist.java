package com.dentalclinic.clinic.domain;

import java.time.Instant;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Perfil profesional 1:1 con {@code User} (RF-PR-01..03, Architecture.md §15.2).
 *
 * <p>Almacena los datos personales del profesional (nombre, apellidos, teléfono) además de los
 * profesionales (especialidad, cédula/licencia, información pública, foto). Architecture.md §15.2
 * excluye nombre/apellidos/teléfono de {@code User}: esos datos viven en el agregado que los
 * necesita, que aquí es {@code Dentist} (el OWNER del onboarding también es un {@code Dentist}).
 *
 * <p><strong>Sin {@code active} propio:</strong> la desactivación del acceso usa {@code User.active}
 * como único flag (decisión de Fase 3). No se duplica el estado.
 *
 * <p>La relación con {@code User} es por identidad ({@code userId}), igual que {@code Invitation},
 * para no acoplar el agregado de identidad al dominio de la clínica.
 */
@Entity
@Table(name = "dentist")
public class Dentist {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    @Column(name = "first_name", nullable = false, length = 100)
    private String firstName;

    @Column(name = "last_name", nullable = false, length = 100)
    private String lastName;

    @Column(name = "phone", nullable = false, length = 50)
    private String phone;

    @Column(name = "specialty", length = 200)
    private String specialty;

    @Column(name = "license_number", length = 100)
    private String licenseNumber;

    @Column(name = "public_info")
    private String publicInfo;

    @Column(name = "photo_url", length = 2048)
    private String photoUrl;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false,
            columnDefinition = "TIMESTAMPTZ DEFAULT now()")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    protected Dentist() {
        // Para JPA.
    }

    private Dentist(Long userId, String firstName, String lastName, String phone, String specialty,
                    String licenseNumber, String publicInfo, String photoUrl) {
        this.userId = Objects.requireNonNull(userId, "userId es obligatorio");
        this.firstName = Objects.requireNonNull(firstName, "firstName es obligatorio");
        this.lastName = Objects.requireNonNull(lastName, "lastName es obligatorio");
        this.phone = Objects.requireNonNull(phone, "phone es obligatorio");
        this.specialty = specialty;
        this.licenseNumber = licenseNumber;
        this.publicInfo = publicInfo;
        this.photoUrl = photoUrl;
    }

    public static Dentist create(Long userId, String firstName, String lastName, String phone,
            String specialty, String licenseNumber, String publicInfo, String photoUrl) {
        return new Dentist(userId, firstName, lastName, phone, specialty, licenseNumber, publicInfo,
                photoUrl);
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

    public String getPhone() {
        return phone;
    }

    public String getSpecialty() {
        return specialty;
    }

    public String getLicenseNumber() {
        return licenseNumber;
    }

    public String getPublicInfo() {
        return publicInfo;
    }

    public String getPhotoUrl() {
        return photoUrl;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}