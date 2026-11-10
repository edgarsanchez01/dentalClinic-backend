package com.dentalclinic.financial.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import com.dentalclinic.financial.application.command.ChargeItemData;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

public record ChargeItemRequest(
        @Positive @JsonDeserialize(using = FinancialIntegerDeserializer.class) Long serviceCatalogItemId,
        @Positive @JsonDeserialize(using = FinancialIntegerDeserializer.class) Long treatingDentistId,
        @NotBlank @Size(max = 4000) String description,
        @NotNull @Min(1) @Max(Integer.MAX_VALUE)
        @JsonDeserialize(using = FinancialIntegerDeserializer.class) Long quantity,
        @NotNull @PositiveOrZero @Digits(integer = 15, fraction = 2) BigDecimal unitPrice)
        implements StrictFinancialRequest {

    public ChargeItemData toData() {
        return new ChargeItemData(serviceCatalogItemId, treatingDentistId, description,
                Math.toIntExact(quantity), unitPrice);
    }
}
