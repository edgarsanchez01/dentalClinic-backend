package com.dentalclinic.medicalintake.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import com.dentalclinic.medicalintake.domain.IntakeAnswers.*;

@Entity
@Table(name = "patient_intake_draft")
public class IntakeDraft {
    @Id @Column(name = "patient_id") private Long patientId;
    @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb") private Medical medical;
    @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb") private Dental dental;

    protected IntakeDraft() { }
    public IntakeDraft(Long patientId) { this.patientId = patientId; }
    public Medical getMedical() { return medical; }
    public Dental getDental() { return dental; }
    public void saveMedical(Medical medical) { this.medical = medical; }
    public void saveDental(Dental dental) { this.dental = dental; }
}
