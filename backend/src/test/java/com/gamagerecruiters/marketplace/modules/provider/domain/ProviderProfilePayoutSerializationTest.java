package com.gamagerecruiters.marketplace.modules.provider.domain;

import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class ProviderProfilePayoutSerializationTest {

    @Test
    void payoutDetails_shouldNotBeIncludedWhenProviderProfileIsSerialized() throws Exception {
        ProviderProfile profile = new ProviderProfile();

        profile.setPayoutBankName("Commercial Bank");
        profile.setPayoutAccountHolderName("Test Account Holder");
        profile.setPayoutAccountNumberEncrypted(
                "v1:encrypted-initialization-vector:encrypted-ciphertext"
        );

        String json = new ObjectMapper().writeValueAsString(profile);

        assertFalse(json.contains("payoutBankName"));
        assertFalse(json.contains("payoutAccountHolderName"));
        assertFalse(json.contains("payoutAccountNumberEncrypted"));
        assertFalse(json.contains("Commercial Bank"));
        assertFalse(json.contains("Test Account Holder"));
        assertFalse(json.contains("encrypted-ciphertext"));
    }
}
