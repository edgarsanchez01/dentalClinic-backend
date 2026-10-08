package com.dentalclinic.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.dentalclinic.identity.domain.exception.PasswordExceedsHashingLimitException;

/**
 * El límite de BCrypt es de <strong>72 bytes UTF-8</strong>, no 72 caracteres.
 *
 * <p>Comportamiento verificado contra {@code BCryptPasswordEncoder} de Spring Security 6.5.x:
 * {@code encode()} lanza sobre 72 bytes y {@code matches()} <em>no</em> aplica el límite (acepta
 * un prefijo de 72 bytes). Por eso la validación es previa al hashing y trabaja en bytes.
 */
class PasswordPolicyTest {

    @Test
    void caracteresAscii_72BytesPasan() {
        assertThat(PasswordPolicy.fitsHashingLimit("c".repeat(72))).isTrue();
    }

    @Test
    void caracteresAscii_73BytesNoPasan() {
        assertThat(PasswordPolicy.fitsHashingLimit("c".repeat(73))).isFalse();
    }

    @Test
    void multibyteConteoEnBytesNoEnCaracteres() {
        // 'á' ocupa 2 bytes UTF-8: 36 caracteres = 72 bytes (pasan).
        assertThat(PasswordPolicy.fitsHashingLimit("á".repeat(36))).isTrue();
        // 37 caracteres = 74 bytes (no pasan), aunque siga siendo corto en caracteres.
        assertThat(PasswordPolicy.fitsHashingLimit("á".repeat(37))).isFalse();
    }

    @Test
    void emoji_18CaracteresPasan_19No() {
        // Cada emoji ocupa 4 bytes UTF-8.
        assertThat(PasswordPolicy.fitsHashingLimit("😀".repeat(18))).isTrue();
        assertThat(PasswordPolicy.fitsHashingLimit("😀".repeat(19))).isFalse();
    }

    @Test
    void requireWithinHashingLimitNoLanzaDentroDelLimite() {
        assertThatCode(() -> PasswordPolicy.requireWithinHashingLimit("correct horse battery staple"))
                .doesNotThrowAnyException();
    }

    @Test
    void requireWithinHashingLimitLanzaFueraDelLimite() {
        assertThatThrownBy(() -> PasswordPolicy.requireWithinHashingLimit("a".repeat(73)))
                .isInstanceOf(PasswordExceedsHashingLimitException.class)
                .hasMessageContaining("72");
    }

    @Test
    void atributosInvariantes() {
        assertThat(PasswordPolicy.MAX_SAFE_UTF8_BYTES).isEqualTo(72);
        assertThat(PasswordPolicy.utf8Length("á".repeat(3))).isEqualTo(6);
    }
}