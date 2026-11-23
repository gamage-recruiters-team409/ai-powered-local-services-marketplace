package com.gamagerecruiters.marketplace.modules.provider.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import io.github.cdimascio.dotenv.Dotenv;

/**
 * AES-256-GCM encryption for provider bank account numbers.
 *
 * The key must be supplied through PROVIDER_PAYOUT_ENCRYPTION_KEY as
 * Base64-encoded 32-byte (256-bit) key material.
 */
@Component
public class ProviderPayoutEncryptionService {

    private static final String KEY_ENVIRONMENT_VARIABLE =
            "PROVIDER_PAYOUT_ENCRYPTION_KEY";

    private static final String CIPHERTEXT_VERSION = "v1";

    private static final int KEY_LENGTH_BYTES = 32;
    private static final int IV_LENGTH_BYTES = 12;
    private static final int AUTHENTICATION_TAG_LENGTH_BITS = 128;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final Environment environment;

    private volatile byte[] cachedEncryptionKey;

    public ProviderPayoutEncryptionService(Environment environment) {
        this.environment = environment;
    }

    public String encrypt(String plainText) {
        if (plainText == null || plainText.isBlank()) {
            throw new IllegalArgumentException(
                    "A non-empty account number is required for encryption"
            );
        }

        try {
            byte[] iv = new byte[IV_LENGTH_BYTES];
            SECURE_RANDOM.nextBytes(iv);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(getEncryptionKey(), "AES"),
                    new GCMParameterSpec(
                            AUTHENTICATION_TAG_LENGTH_BITS,
                            iv
                    )
            );

            cipher.updateAAD(
                    CIPHERTEXT_VERSION.getBytes(StandardCharsets.UTF_8)
            );

            byte[] encryptedBytes = cipher.doFinal(
                    plainText.getBytes(StandardCharsets.UTF_8)
            );

            Base64.Encoder encoder = Base64.getEncoder().withoutPadding();

            return CIPHERTEXT_VERSION
                    + ":"
                    + encoder.encodeToString(iv)
                    + ":"
                    + encoder.encodeToString(encryptedBytes);

        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(
                    "Unable to encrypt provider payout account details",
                    exception
            );
        }
    }

    public String decrypt(String encryptedValue) {
        if (encryptedValue == null || encryptedValue.isBlank()) {
            throw new IllegalArgumentException(
                    "Encrypted provider payout account details are required"
            );
        }

        String[] parts = encryptedValue.split(":", 3);

        if (parts.length != 3 || !CIPHERTEXT_VERSION.equals(parts[0])) {
            throw new IllegalStateException(
                    "Stored provider payout account data has an unsupported format"
            );
        }

        try {
            Base64.Decoder decoder = Base64.getDecoder();

            byte[] iv = decoder.decode(parts[1]);
            byte[] encryptedBytes = decoder.decode(parts[2]);

            if (iv.length != IV_LENGTH_BYTES) {
                throw new IllegalStateException(
                        "Stored provider payout account data has an invalid initialization vector"
                );
            }

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    new SecretKeySpec(getEncryptionKey(), "AES"),
                    new GCMParameterSpec(
                            AUTHENTICATION_TAG_LENGTH_BITS,
                            iv
                    )
            );

            cipher.updateAAD(
                    CIPHERTEXT_VERSION.getBytes(StandardCharsets.UTF_8)
            );

            byte[] plainBytes = cipher.doFinal(encryptedBytes);

            return new String(plainBytes, StandardCharsets.UTF_8);

        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "Unable to decrypt provider payout account details. "
                            + "Check that the original encryption key is configured.",
                    exception
            );
        }
    }

    private byte[] getEncryptionKey() {
        byte[] currentKey = cachedEncryptionKey;

        if (currentKey != null) {
            return currentKey;
        }

        synchronized (this) {
            if (cachedEncryptionKey != null) {
                return cachedEncryptionKey;
            }

            String encodedKey = resolveEncryptionKey();

            final byte[] decodedKey;

            try {
                decodedKey = Base64.getDecoder().decode(encodedKey);
            } catch (IllegalArgumentException exception) {
                throw new IllegalStateException(
                        KEY_ENVIRONMENT_VARIABLE
                                + " must contain a valid Base64-encoded 32-byte key",
                        exception
                );
            }

            if (decodedKey.length != KEY_LENGTH_BYTES) {
                throw new IllegalStateException(
                        KEY_ENVIRONMENT_VARIABLE
                                + " must decode to exactly 32 bytes (256 bits)"
                );
            }

            cachedEncryptionKey = decodedKey;
            return cachedEncryptionKey;
        }
    }

    private String resolveEncryptionKey() {
        String value = environment.getProperty(KEY_ENVIRONMENT_VARIABLE);

        if (hasText(value)) {
            return value.trim();
        }

        // Supports running Spring Boot while the working directory is backend/.
        Dotenv currentDirectory = Dotenv.configure()
                .ignoreIfMissing()
                .ignoreIfMalformed()
                .load();

        value = currentDirectory.get(KEY_ENVIRONMENT_VARIABLE);

        if (hasText(value)) {
            return value.trim();
        }

        // Supports running Spring Boot from the repository root.
        Dotenv backendDirectory = Dotenv.configure()
                .directory("backend")
                .ignoreIfMissing()
                .ignoreIfMalformed()
                .load();

        value = backendDirectory.get(KEY_ENVIRONMENT_VARIABLE);

        if (hasText(value)) {
            return value.trim();
        }

        throw new IllegalStateException(
                "Missing " + KEY_ENVIRONMENT_VARIABLE
                        + ". Configure it as an environment variable "
                        + "or in backend/.env before saving payout details."
        );
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
