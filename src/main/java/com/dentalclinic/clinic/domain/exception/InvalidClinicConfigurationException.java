package com.dentalclinic.clinic.domain.exception;

/**
 * Una configuración de la clínica no es válida (400). En particular, una zona horaria que no es un
 * identificador IANA reconocido por la JVM.
 *
 * <p>RF-CL-05 exige zona horaria IANA y prohíbe caer al default de la JVM: aceptar un valor
 * inválido y sustituirlo silenciosamente produciría "días" y "horarios" equivocados, así que se
 * rechaza de forma explícita.
 */
public class InvalidClinicConfigurationException extends RuntimeException {

    public InvalidClinicConfigurationException(String message) {
        super(message);
    }
}