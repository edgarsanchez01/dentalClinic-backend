package com.dentalclinic.identity.infrastructure.token;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Tokens opacos: 256 bits de entropía, no legibles, persistidos solo como hash SHA-256.
 */
class TokenHasherTest {

    private final TokenHasher tokenHasher = new TokenHasher();

    @Test
    void tokenGeneradoEsOpaque_UrlSafeYSinPadding() {
        String token = tokenHasher.generateOpaqueToken();
        assertThat(token).matches("^[A-Za-z0-9_-]+$");
        assertThat(token.length()).isGreaterThanOrEqualTo(40);
        assertThat(token).doesNotContain("=", "+", "/");
    }

    @Test
    void cuarentaTokens_NoSeRepiten() {
        Set<String> tokens = new HashSet<>();
        for (int i = 0; i < 40; i++) {
            tokens.add(tokenHasher.generateOpaqueToken());
        }
        assertThat(tokens).hasSize(40);
    }

    @Test
    void hashEsDeterministaYDeLongitudSha256() {
        String token = tokenHasher.generateOpaqueToken();
        assertThat(tokenHasher.hash(token)).isEqualTo(tokenHasher.hash(token)).hasSize(64);
    }

    @Test
    void hashNuncaDevuelveElTokenEnClaro() {
        String token = tokenHasher.generateOpaqueToken();
        assertThat(tokenHasher.hash(token)).isNotEqualTo(token).doesNotContain(token);
    }

    @Test
    void tokenDistinto_ProduceHashDistinto() {
        String a = tokenHasher.generateOpaqueToken();
        String b = tokenHasher.generateOpaqueToken();
        assertThat(a).isNotEqualTo(b);
        assertThat(tokenHasher.hash(a)).isNotEqualTo(tokenHasher.hash(b));
    }
}