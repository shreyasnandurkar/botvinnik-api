package com.shreyasnandurkar.botvinnikapi.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Fast hashing is correct for gateway keys (§11): they carry 256 bits of random
 * entropy, so there is nothing for bcrypt/argon2 slowness to defend.
 */
public final class Sha256 {

    private Sha256() {
    }

    public static String hex(String input) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
