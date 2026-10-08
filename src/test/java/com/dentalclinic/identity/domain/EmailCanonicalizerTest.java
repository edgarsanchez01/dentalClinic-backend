package com.dentalclinic.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Canonicalización de identidad: {@code trim + lowercase} en locale independiente.
 *
 * <p>Regla técnica de Fase 2 (no de producto): el valor persistido y buscado siempre es el
 * canónico. La clave es que {@code Ana@Clinica.COM} y {@code ana@clinica.com} son la misma
 * identidad, y que el resultado no depende del locale de la JVM.
 */
class EmailCanonicalizerTest {

    @Test
    void recortaEspaciosExteriores() {
        assertThat(EmailCanonicalizer.canonicalize("  ana@clinica.com\t")).isEqualTo("ana@clinica.com");
    }

    @Test
    void bajaAMinusculas() {
        assertThat(EmailCanonicalizer.canonicalize("Ana@Clinica.COM")).isEqualTo("ana@clinica.com");
    }

    @Test
    void identidad_InmutableYReversible() {
        String canonical = EmailCanonicalizer.canonicalize("Paciente@Clinica.es");
        assertThat(EmailCanonicalizer.canonicalize(canonical)).isEqualTo(canonical);
    }

    @Test
    void noAplicaReglasDeProveedor() {
        // Ni puntos, ni alias '+', ni dominios: eso seria inventar decision de producto.
        assertThat(EmailCanonicalizer.canonicalize("juan.perez+alias@Gmail.com"))
                .isEqualTo("juan.perez+alias@gmail.com");
    }

    @Test
    void localeIndependiente_Turquia() {
        // La 'I' latina en turco se convertiria en 'I' con punto. Locale.ROOT evita que el
        // resultado cambie segun donde corra la JVM.
        String turkish = EmailCanonicalizer.canonicalize("A.I@clinica.com");
        assertThat(turkish).isEqualTo("a.i@clinica.com");
    }

    @Test
    void nullableLanzaNpe() {
        assertThatThrownBy(() -> EmailCanonicalizer.canonicalize(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("email");
    }
}