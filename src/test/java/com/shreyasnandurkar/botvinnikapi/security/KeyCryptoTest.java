package com.shreyasnandurkar.botvinnikapi.security;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KeyCryptoTest {

    private static String randomKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    @Test
    void roundTripsAndNeverStoresPlaintext() {
        KeyCrypto crypto = new KeyCrypto(randomKey());
        KeyCrypto.Encrypted enc = crypto.encrypt("AIza-super-secret");
        assertThat(enc.ciphertext()).doesNotContain("AIza-super-secret");
        assertThat(crypto.decrypt(enc.ciphertext(), enc.nonce())).isEqualTo("AIza-super-secret");
    }

    @Test
    void freshNoncePerRecord() {
        KeyCrypto crypto = new KeyCrypto(randomKey());
        KeyCrypto.Encrypted first = crypto.encrypt("same-plaintext");
        KeyCrypto.Encrypted second = crypto.encrypt("same-plaintext");
        assertThat(first.nonce()).isNotEqualTo(second.nonce());
        assertThat(first.ciphertext()).isNotEqualTo(second.ciphertext());
    }

    @Test
    void tamperedCiphertextIsRejected() {
        KeyCrypto crypto = new KeyCrypto(randomKey());
        KeyCrypto.Encrypted enc = crypto.encrypt("secret");
        byte[] bytes = Base64.getDecoder().decode(enc.ciphertext());
        bytes[0] ^= 1;
        String tampered = Base64.getEncoder().encodeToString(bytes);
        assertThatThrownBy(() -> crypto.decrypt(tampered, enc.nonce()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void wrongKeyCannotDecrypt() {
        KeyCrypto.Encrypted enc = new KeyCrypto(randomKey()).encrypt("secret");
        KeyCrypto other = new KeyCrypto(randomKey());
        assertThatThrownBy(() -> other.decrypt(enc.ciphertext(), enc.nonce()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsWrongSizedKeys() {
        assertThatThrownBy(() -> new KeyCrypto(Base64.getEncoder().encodeToString(new byte[16])))
                .isInstanceOf(IllegalStateException.class);
    }
}
