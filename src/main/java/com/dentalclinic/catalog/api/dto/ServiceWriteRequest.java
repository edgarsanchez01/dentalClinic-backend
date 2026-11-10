package com.dentalclinic.catalog.api.dto;

import java.math.BigDecimal;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record ServiceWriteRequest(
        @NotBlank(message = "El nombre es obligatorio.")
        @Size(max = 200, message = "El nombre admite hasta 200 caracteres.") String name,
        @Size(max = 4000, message = "La descripción admite hasta 4000 caracteres.") String description,
        @NotNull(message = "El precio de referencia es obligatorio.")
        @DecimalMin(value = "0", message = "El precio de referencia no puede ser negativo.")
        @Digits(integer = 15, fraction = 4, message = "El precio admite hasta 15 enteros y 4 decimales.") BigDecimal referencePrice,
        @NotNull(message = "La duración es obligatoria.")
        @Positive(message = "La duración debe ser mayor que cero.")
        @JsonDeserialize(using = IntegerMinutesDeserializer.class) Integer durationMinutes) { }
