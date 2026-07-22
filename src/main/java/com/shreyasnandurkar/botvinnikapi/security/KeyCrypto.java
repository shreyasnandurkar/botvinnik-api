package com.shreyasnandurkar.botvinnikapi.security;

import com.shreyasnandurkar.botvinnikapi.config.GatewayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-GCM-256 for provider keys at rest (§11) — reversible because they go upstream.
 * Fresh random nonce per record: GCM nonce reuse leaks the auth key itself.
 */
@Component
public class KeyCrypto {

    private static final Logger log = LoggerFactory.getLogger(KeyCrypto.class);
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    @Autowired
    public KeyCrypto(GatewayProperties properties) {
        this(properties.security().encryptionKey());
    }

    KeyCrypto(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            log.warn("GATEWAY_ENCRYPTION_KEY is not set — using a fixed development key. "
                    + "Provider credentials are NOT protected against DB-plus-code access.");
            this.key = new SecretKeySpec(sha256("botvinnik-dev-encryption-key"), "AES");
        } else {
            byte[] bytes = Base64.getDecoder().decode(base64Key);
            if (bytes.length != 32) {
                throw new IllegalStateException("Encryption key must be 32 bytes (base64-encoded), got " + bytes.length);
            }
            this.key = new SecretKeySpec(bytes, "AES");
        }
    }

    public record Encrypted(String ciphertext, String nonce) {
    }

    public Encrypted encrypt(String plaintext) {
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] out = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return new Encrypted(Base64.getEncoder().encodeToString(out),
                    Base64.getEncoder().encodeToString(nonce));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encrypt provider key", e);
        }
    }

    public String decrypt(String ciphertext, String nonce) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key,
                    new GCMParameterSpec(TAG_BITS, Base64.getDecoder().decode(nonce)));
            return new String(cipher.doFinal(Base64.getDecoder().decode(ciphertext)), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to decrypt provider key (wrong GATEWAY_ENCRYPTION_KEY?)", e);
        }
    }

    private static byte[] sha256(String input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
