package com.gamagerecruiters.marketplace.modules.provider.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Base64;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ProviderPayoutEncryptionServiceTest {

    private static final String TEST_KEY =
            Base64.getEncoder().encodeToString(new byte[32]);

    private ProviderPayoutEncryptionService encryptionService;

    @BeforeEach
    void setUp() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty(
                "PROVIDER_PAYOUT_ENCRYPTION_KEY",
                TEST_KEY
        );

        encryptionService =
                new ProviderPayoutEncryptionService(environment);
    }

    @Test
    void encryptAndDecrypt_shouldReturnOriginalAccountNumber() {
        String accountNumber = "123456789012";

        String encrypted = encryptionService.encrypt(accountNumber);

        assertNotEquals(accountNumber, encrypted);
        assertEquals(
                accountNumber,
                encryptionService.decrypt(encrypted)
        );
    }

    @Test
    void encrypt_shouldUseDifferentInitializationVectorForEachEncryption() {
        String accountNumber = "123456789012";

        String firstEncryption = encryptionService.encrypt(accountNumber);
        String secondEncryption = encryptionService.encrypt(accountNumber);

        assertNotEquals(firstEncryption, secondEncryption);

        assertEquals(
                accountNumber,
                encryptionService.decrypt(firstEncryption)
        );

        assertEquals(
                accountNumber,
                encryptionService.decrypt(secondEncryption)
        );
    }

    @Test
    void decrypt_shouldRejectTamperedCiphertext() {
        String encrypted = encryptionService.encrypt("123456789012");
        String[] parts = encrypted.split(":", 3);

        byte[] ciphertext = Base64.getDecoder().decode(parts[2]);
        ciphertext[0] = (byte) (ciphertext[0] ^ 1);

        String tampered = parts[0]
                + ":"
                + parts[1]
                + ":"
                + Base64.getEncoder().withoutPadding().encodeToString(ciphertext);

        assertThrows(
                IllegalStateException.class,
                () -> encryptionService.decrypt(tampered)
        );
    }

    @Test
    void decrypt_shouldRejectUnsupportedCiphertextVersion() {
        assertThrows(
                IllegalStateException.class,
                () -> encryptionService.decrypt("v2:abc:def")
        );
    }
}
