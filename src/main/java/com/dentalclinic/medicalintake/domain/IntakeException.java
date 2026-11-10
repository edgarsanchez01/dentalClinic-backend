package com.dentalclinic.medicalintake.domain;

import java.util.Map;

/** Only server-authored messages/field names; never interpolates submitted medical values. */
public class IntakeException extends RuntimeException {
    private final String code;
    private final Map<String, String> fieldErrors;

    public IntakeException(String code, String message) { this(code, message, Map.of()); }
    public IntakeException(String code, String message, Map<String, String> fieldErrors) {
        super(message);
        this.code = code;
        this.fieldErrors = Map.copyOf(fieldErrors);
    }
    public String code() { return code; }
    public Map<String, String> fieldErrors() { return fieldErrors; }
}
