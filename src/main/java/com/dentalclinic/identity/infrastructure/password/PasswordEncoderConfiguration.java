package com.dentalclinic.identity.infrastructure.password;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.dentalclinic.identity.domain.PasswordPolicy;

/**
 * Adaptador de hashing de contraseñas.
 *
 * <p>RF-IA-13 exige "hash adaptativo con sal (BCrypt o Argon2)" y Architecture.md §7.1 fija
 * "BCrypt (o Argon2). Nunca en claro.". Se usa <strong>BCrypt</strong> porque Argon2 en Spring
 * Security requiere BouncyCastle, que no está en el stack declarado (Architecture.md §5.1) y
 * que nadie ha aprobado añadir.
 *
 * <p>La restricción de 72 bytes UTF-8 no la aplica el encoder de forma homogénea
 * ({@code encode()} lanza, {@code matches()} trunca en silencio), por eso el bean de
 * validación vive explícitamente aparte: {@link PasswordPolicy} la comprueba <em>antes</em> de
 * hashear. Ver la documentación de {@code PasswordPolicy} para el comportamiento medido.
 */
@Configuration(proxyBeanMethods = false)
public class PasswordEncoderConfiguration {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
