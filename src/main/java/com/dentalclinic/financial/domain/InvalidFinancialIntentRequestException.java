package com.dentalclinic.financial.domain;

/** Formato B4; no contiene valores recibidos, comandos ni evidencia de rechazo financiero. */
public final class InvalidFinancialIntentRequestException extends RuntimeException {
    private final String code;

    public InvalidFinancialIntentRequestException(boolean cursor) {
        super(cursor ? "El cursor no es valido para esta consulta. Inicia una nueva consulta."
                : "Revisa el formato y los campos de la intencion financiera.");
        code = cursor ? "INTENT_CURSOR_INVALID" : "INTENT_REQUEST_INVALID";
    }

    public String code() { return code; }
}
