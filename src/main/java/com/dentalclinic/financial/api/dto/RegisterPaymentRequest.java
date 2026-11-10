package com.dentalclinic.financial.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import com.dentalclinic.financial.domain.PaymentMethod;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

public record RegisterPaymentRequest(@NotNull @JsonDeserialize(using = FinancialPaymentMethodDeserializer.class) PaymentMethod method,
        @NotNull @Positive @Digits(integer = 15, fraction = 2) BigDecimal amount,
        String reason) implements StrictFinancialRequest {
}
