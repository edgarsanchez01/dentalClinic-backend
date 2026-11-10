package com.dentalclinic.checkout.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

import com.dentalclinic.appointment.domain.AppointmentStatus;
import com.dentalclinic.checkout.domain.ChargeDisposition;
import com.dentalclinic.financial.domain.ChargeStatus;
import com.dentalclinic.financial.domain.PaymentMethod;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Respuesta del cierre integrado, tambien persistida como snapshot de idempotencia.
 *
 * <p>Refleja el estado financiero <strong>al momento del cierre</strong>, no una lectura futura del
 * cargo. Un replay devuelve exactamente este snapshot aunque el cargo haya recibido pagos o
 * reversas despues.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AppointmentCheckoutResponse(Long checkoutId, CheckoutAppointment appointment,
        CheckoutCharge charge, CheckoutPayment newPayment, CheckoutFinancialState financialAtCheckout) {

    public record CheckoutAppointment(Long id, AppointmentStatus status, int occurrenceVersion,
            Instant completedAt) {
    }

    public record CheckoutCharge(@com.fasterxml.jackson.annotation.JsonProperty("id") Long chargeId,
            ChargeDisposition disposition) {
    }

    public record CheckoutPayment(@com.fasterxml.jackson.annotation.JsonProperty("id") Long paymentId,
            PaymentMethod method, BigDecimal amount) {
    }

    public record CheckoutFinancialState(String currency, BigDecimal totalAmount,
            BigDecimal appliedAmount, BigDecimal pendingBalance, ChargeStatus status) {
    }
}
