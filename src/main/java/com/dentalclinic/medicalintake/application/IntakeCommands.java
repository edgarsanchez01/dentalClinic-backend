package com.dentalclinic.medicalintake.application;

import java.util.UUID;
import jakarta.validation.constraints.*;
import com.dentalclinic.patient.domain.IntakePersonalData;
import com.dentalclinic.medicalintake.domain.IntakeAnswers.*;

public final class IntakeCommands {
    private IntakeCommands() { }
    public record Personal(@NotNull @PositiveOrZero Long expectedVersion, @NotNull IntakePersonalData personal) {
        @Override public String toString() { return "Personal[redacted]"; }
    }
    public record MedicalSave(@NotNull @PositiveOrZero Long expectedVersion, @NotNull Medical medical) {
        @Override public String toString() { return "MedicalSave[redacted]"; }
    }
    public record DentalSave(@NotNull @PositiveOrZero Long expectedVersion, @NotNull Dental dental) {
        @Override public String toString() { return "DentalSave[redacted]"; }
    }
    public record Privacy(@NotNull @PositiveOrZero Long expectedVersion, @NotBlank @Size(max=80) String noticeVersion,
                          @NotNull Boolean acknowledged, @NotNull Boolean processingAccepted) { }
    public record Confirmation(@NotNull @PositiveOrZero Long expectedVersion, @NotNull UUID operationId,
            @NotBlank @Size(max=80) String questionnaireVersion, @NotBlank @Size(max=80) String declarationVersion,
            @NotNull Boolean declarationConfirmed, @NotNull ConfirmedBy confirmedBy) { }
}
