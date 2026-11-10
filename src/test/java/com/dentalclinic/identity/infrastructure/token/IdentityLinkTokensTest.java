package com.dentalclinic.identity.infrastructure.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.dentalclinic.identity.infrastructure.config.IdentityLinkProperties;
import com.dentalclinic.identity.infrastructure.token.IdentityLinkTokens.Domain;
import com.dentalclinic.identity.infrastructure.token.IdentityLinkTokens.IssuedLinkToken;

/**
 * Tokens de enlace de identidad (F8B/T1): reponibles por HMAC, con nonce único por emisión y
 * separación de dominios.
 *
 * <p>Se prueban sin Spring: el componente es una función determinista de la clave y la terna
 * {@code (dominio, userId, nonce)}.
 */
class IdentityLinkTokensTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private static final String LEGACY_FORM = "[A-Za-z0-9_-]{43}";

    private final TokenHasher hasher = new TokenHasher();

    private IdentityLinkTokens tokens(String secret) {
        return new IdentityLinkTokens(new IdentityLinkProperties("https://clinic.example.test", secret, true), hasher);
    }

    private IdentityLinkTokens tokensWithoutSecret() {
        return new IdentityLinkTokens(new IdentityLinkProperties("", "", false), hasher);
    }

    @Test
    void reconstruccion_EsDeterminista() {
        IdentityLinkTokens tokens = tokens(SECRET);
        String nonce = tokens.newNonce();
        String once = tokens.token(Domain.STAFF_INVITATION, 7L, nonce);
        String twice = tokens.token(Domain.STAFF_INVITATION, 7L, nonce);
        assertThat(once).isEqualTo(twice).matches(LEGACY_FORM);
    }

    @Test
    void emisionesDistintas_ProducenTokensYNoncesDistintos() {
        IdentityLinkTokens tokens = tokens(SECRET);
        IssuedLinkToken first = tokens.issue(Domain.PASSWORD_RESET, 7L);
        IssuedLinkToken second = tokens.issue(Domain.PASSWORD_RESET, 7L);
        assertThat(first.nonce()).isNotEqualTo(second.nonce());
        assertThat(first.token()).isNotEqualTo(second.token());
    }

    @Test
    void nonce_NoDependeSoloDelUsuario() {
        IdentityLinkTokens tokens = tokens(SECRET);
        Set<String> nonces = new HashSet<>();
        for (int i = 0; i < 40; i++) {
            nonces.add(tokens.issue(Domain.STAFF_INVITATION, 99L).nonce());
        }
        assertThat(nonces).hasSize(40);
    }

    @Test
    void separacionDeDominios_MismoUsuarioYNonce_ProduceTokensDistintos() {
        IdentityLinkTokens tokens = tokens(SECRET);
        String nonce = tokens.newNonce();
        assertThat(tokens.token(Domain.STAFF_INVITATION, 7L, nonce))
                .isNotEqualTo(tokens.token(Domain.PASSWORD_RESET, 7L, nonce));
    }

    @Test
    void usuariosDistintos_ConMismoNonce_ProducenTokensDistintos() {
        IdentityLinkTokens tokens = tokens(SECRET);
        String nonce = tokens.newNonce();
        assertThat(tokens.token(Domain.PASSWORD_RESET, 1L, nonce))
                .isNotEqualTo(tokens.token(Domain.PASSWORD_RESET, 2L, nonce));
    }

    @Test
    void claveDistinta_ProduceTokenDistinto() {
        String nonce = new TokenHasher().generateOpaqueToken();
        String a = tokens(SECRET).token(Domain.STAFF_INVITATION, 7L, nonce);
        String b = tokens("ffffffffffffffffffffffffffffffff").token(Domain.STAFF_INVITATION, 7L, nonce);
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void nonce_TieneFormaDe256BitsEnBase64Url() {
        assertThat(tokens(SECRET).newNonce()).matches(LEGACY_FORM);
    }

    @Test
    void hash_EsSha256YNoElToken() {
        IdentityLinkTokens tokens = tokens(SECRET);
        String token = tokens.issue(Domain.PASSWORD_RESET, 3L).token();
        assertThat(tokens.hash(token)).hasSize(64).isNotEqualTo(token).doesNotContain(token);
    }

    @Test
    void tokenLegadoYTokenHmac_CompartenFormaYSePersistenIgual() {
        // Compatibilidad: el token aleatorio de Fase 2 y el token HMAC de F8B tienen la misma forma
        // (43 Base64 URL-safe) y ambos se persisten como SHA-256 de 64 caracteres, que es la columna
        // real (invitations/password_reset_tokens). El consumo por hash no cambia.
        String legacy = new TokenHasher().generateOpaqueToken();
        String hmac = tokens(SECRET).issue(Domain.STAFF_INVITATION, 5L).token();
        assertThat(legacy).matches(LEGACY_FORM);
        assertThat(hmac).matches(LEGACY_FORM);
        assertThat(tokens(SECRET).hash(legacy)).hasSize(64);
        assertThat(tokens(SECRET).hash(hmac)).hasSize(64);
    }

    @Test
    void sinClave_configuradoEsFalseYFalla() {
        IdentityLinkTokens tokens = tokensWithoutSecret();
        assertThat(tokens.configured()).isFalse();
        assertThat(tokens.newNonce()).matches(LEGACY_FORM);
        assertThatThrownBy(() -> tokens.token(Domain.STAFF_INVITATION, 7L, tokens.newNonce()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> tokens.issue(Domain.PASSWORD_RESET, 7L))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void nonceInvalido_Falla() {
        IdentityLinkTokens tokens = tokens(SECRET);
        assertThatThrownBy(() -> tokens.token(Domain.STAFF_INVITATION, 7L, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tokens.token(Domain.STAFF_INVITATION, 7L, "no-es-un-nonce!!"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void usuarioInvalido_Falla() {
        IdentityLinkTokens tokens = tokens(SECRET);
        assertThatThrownBy(() -> tokens.token(Domain.STAFF_INVITATION, 0L, tokens.newNonce()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tokens.token(Domain.STAFF_INVITATION, -1L, tokens.newNonce()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void issuedLinkToken_NoExponeTokenNiNonce() {
        IdentityLinkTokens tokens = tokens(SECRET);
        IssuedLinkToken issued = tokens.issue(Domain.PASSWORD_RESET, 7L);
        String rendered = issued.toString();
        assertThat(rendered).doesNotContain(issued.token()).doesNotContain(issued.nonce());
    }
}
