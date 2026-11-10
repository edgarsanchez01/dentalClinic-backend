package com.dentalclinic.medicalintake.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Immutable prototype texts. A text change requires a NEW version, never editing an issued one. */
public final class IntakeDefinition {
    public static final String QUESTIONNAIRE_VERSION = "dental-intake-demo-v1";
    public static final String NOTICE_VERSION = "intake-privacy-demo-v1";
    public static final String DECLARATION_VERSION = "intake-declaration-demo-v1";
    public static final String NOTICE_TEXT = """
            Prototipo demostrativo: no introduzcas datos reales de salud. Las respuestas y sus revisiones
            se guardan en el servidor y pueden ser consultadas por el personal odontológico autorizado.
            Este aviso y las acciones de confirmación están pendientes de revisión jurídica; no acreditan
            cumplimiento legal. La aceptación se refiere al guardado de datos del prototipo, no a
            procedimientos odontológicos. Para menores, el adulto responsable utiliza una cuenta para
            un solo paciente; los datos introducidos no verifican ni acreditan representación legal.
            """.strip();
    public static final String DECLARATION_TEXT = """
            Confirmo que revisé estas respuestas autodeclaradas y que reflejan lo que conozco del paciente.
            Puedo indicar que desconozco una respuesta o prefiero hablarla en consulta. Esta declaración
            no es un diagnóstico ni un consentimiento para procedimientos odontológicos. Si actúo como
            adulto responsable de un menor, mis datos no acreditan legalmente la representación.
            Declaración de prototipo pendiente de revisión odontológica y jurídica.
            """.strip();

    private IntakeDefinition() { }
    public static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 no disponible"); }
    }
}
