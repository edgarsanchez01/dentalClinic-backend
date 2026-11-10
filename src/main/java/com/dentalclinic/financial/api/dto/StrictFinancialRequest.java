package com.dentalclinic.financial.api.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;

/** Rechaza campos ignorados que podrian cambiar la intencion sin cambiar el hash de solicitud. */
public interface StrictFinancialRequest {
    @JsonAnySetter
    default void rejectUnknown(String name, Object value) {
        throw new IllegalArgumentException("La solicitud contiene campos no admitidos.");
    }
}
