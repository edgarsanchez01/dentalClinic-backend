package com.dentalclinic.checkout.api.dto;

import java.math.BigDecimal;

import com.dentalclinic.checkout.domain.ChargeAction;
import com.dentalclinic.checkout.domain.PaymentAction;
import com.dentalclinic.financial.domain.PaymentMethod;

/**
 * Cuerpo estricto del cierre integrado. No contiene IDs de paciente/dentista, estado, saldo ni
 * importe fuente: solo la precondicion de ocurrencia, el token de contexto, la accion sobre UN
 * cargo y, opcionalmente, UN pago nuevo. El parser dedicado rechaza campos desconocidos.
 */
public record AppointmentCheckoutRequest(Integer expectedOccurrenceVersion, String financialContextToken,
        ChargeRequest charge, PaymentRequest payment) {

    public record ChargeRequest(ChargeAction action, Long chargeId, BigDecimal expectedTotal,
            Boolean confirmed) {
    }

    public record PaymentRequest(PaymentAction action, PaymentMethod method, BigDecimal amount) {
    }
}
