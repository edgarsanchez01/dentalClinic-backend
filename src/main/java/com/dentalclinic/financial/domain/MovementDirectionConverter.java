package com.dentalclinic.financial.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Persiste {@link MovementDirection} con los valores de una sola letra del esquema aprobado
 * ({@code V016}: {@code direction VARCHAR(1)} con {@code CHECK (direction IN ('I','O'))}).
 *
 * <p>El dominio conserva el vocabulario legible {@code IN}/{@code OUT}; el mapeo a {@code I}/{@code O}
 * se hace aqui para no tocar el DDL ya revisado ni relajar su restriccion.
 */
@Converter
public class MovementDirectionConverter implements AttributeConverter<MovementDirection, String> {

    @Override
    public String convertToDatabaseColumn(MovementDirection attribute) {
        if (attribute == null) {
            return null;
        }
        return switch (attribute) {
            case IN -> "I";
            case OUT -> "O";
        };
    }

    @Override
    public MovementDirection convertToEntityAttribute(String dbData) {
        if (dbData == null) {
            return null;
        }
        return switch (dbData) {
            case "I" -> MovementDirection.IN;
            case "O" -> MovementDirection.OUT;
            default -> throw new IllegalArgumentException(
                    "direccion de movimiento desconocida en base: " + dbData);
        };
    }
}
