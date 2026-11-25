package com.gamagerecruiters.marketplace.modules.ai.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

@ExtendWith(MockitoExtension.class)
class DefaultAiGatewayValidationTest {

    private static final String PROVIDER = "test-provider";
    private static final String MODEL = "test-model";
    private static final String API_KEY = "sk-test-do-not-leak-1234567890";
    private static final int MAX_INPUT = 40;
    private static final int MAX_CONTEXT = 60;

    @Mock
    private ObjectProvider<AiProviderClient> providerClients;
    @Mock
    private AiProviderClient client;

    private DefaultAiGateway gateway;

    @BeforeEach
    void setUp() {
        gateway = new DefaultAiGateway(providerClients, PROVIDER, MODEL, API_KEY,
                15000L, MAX_INPUT, MAX_CONTEXT);
    }

    @Test
    @DisplayName("null request is rejected as INVALID_REQUEST before any client lookup")
    void nullRequestIsRejectedWithoutProviderClientCall() {
        assertThatThrownBy(() -> gateway.generate(null))
                .isInstanceOf(AiGatewayException.class)
                .satisfies(error -> {
                    assertThat(((AiGatewayException) error).getErrorCode())
                            .isEqualTo(AiGatewayErrorCode.INVALID_REQUEST);
                    assertThat(error.getMessage()).doesNotContain(API_KEY);
                });

        verifyNoInteractions(providerClients, client);
    }

    @Test
    @DisplayName("null feature is rejected as INVALID_REQUEST before any client call")
    void nullFeatureIsRejectedWithoutProviderClientCall() {
        AiGenerationRequest request = new AiGenerationRequest(null, "input", "context", AiTone.PROFESSIONAL,
                AiLength.MEDIUM);

        assertThatThrownBy(() -> gateway.generate(request))
                .isInstanceOf(AiGatewayException.class)
                .satisfies(error -> assertThat(((AiGatewayException) error).getErrorCode())
                        .isEqualTo(AiGatewayErrorCode.INVALID_REQUEST));

        verifyNoInteractions(providerClients, client);
    }

    @Test
    @DisplayName("a feature outside the explicit allowlist is rejected as FEATURE_NOT_ALLOWED")
    void disallowedFeatureIsRejectedWithoutProviderClientCall() {
        AiFeature unapprovedFeature = mock(AiFeature.class);
        AiGenerationRequest request = new AiGenerationRequest(unapprovedFeature, "input", "context",
                AiTone.PROFESSIONAL, AiLength.MEDIUM);

        assertThatThrownBy(() -> gateway.generate(request))
                .isInstanceOf(AiGatewayException.class)
                .satisfies(error -> {
                    assertThat(((AiGatewayException) error).getErrorCode())
                            .isEqualTo(AiGatewayErrorCode.FEATURE_NOT_ALLOWED);
                    assertThat(error.getMessage()).doesNotContain(API_KEY, "input", "context");
                });

        verifyNoInteractions(providerClients, client);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "   ", "\t", "\n"})
    @DisplayName("blank input is rejected as INVALID_REQUEST before any client call")
    void blankInputIsRejectedWithoutProviderClientCall(String input) {
        AiGenerationRequest request = new AiGenerationRequest(AiFeature.LISTING_DESCRIPTION_ASSISTANT,
                input, "context", AiTone.PROFESSIONAL, AiLength.MEDIUM);

        assertThatThrownBy(() -> gateway.generate(request))
                .isInstanceOf(AiGatewayException.class)
                .satisfies(error -> assertThat(((AiGatewayException) error).getErrorCode())
                        .isEqualTo(AiGatewayErrorCode.INVALID_REQUEST));

        verifyNoInteractions(providerClients, client);
    }

    @Test
    @DisplayName("oversized input is rejected as INVALID_REQUEST before any client call")
    void oversizedInputIsRejectedWithoutProviderClientCall() {
        AiGenerationRequest request = new AiGenerationRequest(AiFeature.LISTING_DESCRIPTION_ASSISTANT,
                "x".repeat(MAX_INPUT + 1), "context", AiTone.PROFESSIONAL, AiLength.MEDIUM);

        assertThatThrownBy(() -> gateway.generate(request))
                .isInstanceOf(AiGatewayException.class)
                .satisfies(error -> {
                    assertThat(((AiGatewayException) error).getErrorCode())
                            .isEqualTo(AiGatewayErrorCode.INVALID_REQUEST);
                    assertThat(error.getMessage()).doesNotContain("x".repeat(MAX_INPUT + 1));
                });

        verifyNoInteractions(providerClients, client);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "   ", "\t", "\n"})
    @DisplayName("blank context is rejected as INVALID_REQUEST before any client call")
    void blankContextIsRejectedWithoutProviderClientCall(String context) {
        AiGenerationRequest request = new AiGenerationRequest(AiFeature.DISPUTE_SUMMARY,
                "input", context, AiTone.FRIENDLY, AiLength.SHORT);

        assertThatThrownBy(() -> gateway.generate(request))
                .isInstanceOf(AiGatewayException.class)
                .satisfies(error -> assertThat(((AiGatewayException) error).getErrorCode())
                        .isEqualTo(AiGatewayErrorCode.INVALID_REQUEST));

        verifyNoInteractions(providerClients, client);
    }

    @Test
    @DisplayName("oversized context is rejected as INVALID_REQUEST before any client call")
    void oversizedContextIsRejectedWithoutProviderClientCall() {
        String oversizedContext = "c".repeat(MAX_CONTEXT + 1);
        AiGenerationRequest request = new AiGenerationRequest(AiFeature.DISPUTE_SUMMARY,
                "input", oversizedContext, AiTone.FRIENDLY, AiLength.SHORT);

        assertThatThrownBy(() -> gateway.generate(request))
                .isInstanceOf(AiGatewayException.class)
                .satisfies(error -> {
                    assertThat(((AiGatewayException) error).getErrorCode())
                            .isEqualTo(AiGatewayErrorCode.INVALID_REQUEST);
                    assertThat(error.getMessage()).doesNotContain(oversizedContext);
                });

        verifyNoInteractions(providerClients, client);
    }

    @Test
    @DisplayName("null tone is rejected as INVALID_REQUEST before any client call")
    void nullToneIsRejectedWithoutProviderClientCall() {
        AiGenerationRequest request = new AiGenerationRequest(
                AiFeature.LISTING_DESCRIPTION_ASSISTANT, "input", "context", null, AiLength.MEDIUM);

        assertThatThrownBy(() -> gateway.generate(request))
                .isInstanceOf(AiGatewayException.class)
                .satisfies(error -> assertThat(((AiGatewayException) error).getErrorCode())
                        .isEqualTo(AiGatewayErrorCode.INVALID_REQUEST));

        verifyNoInteractions(providerClients, client);
    }

    @Test
    @DisplayName("null length is rejected as INVALID_REQUEST before any client call")
    void nullLengthIsRejectedWithoutProviderClientCall() {
        AiGenerationRequest request = new AiGenerationRequest(
                AiFeature.LISTING_DESCRIPTION_ASSISTANT, "input", "context", AiTone.PROFESSIONAL, null);

        assertThatThrownBy(() -> gateway.generate(request))
                .isInstanceOf(AiGatewayException.class)
                .satisfies(error -> assertThat(((AiGatewayException) error).getErrorCode())
                        .isEqualTo(AiGatewayErrorCode.INVALID_REQUEST));

        verifyNoInteractions(providerClients, client);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "   ", "\t"})
    @DisplayName("blank provider is NOT_CONFIGURED with a safe message and no client call")
    void blankProviderIsNotConfiguredWithoutProviderClientCall(String provider) {
        DefaultAiGateway unconfigured = new DefaultAiGateway(providerClients, provider, MODEL, API_KEY,
                15000L, MAX_INPUT, MAX_CONTEXT);

        assertNotConfigured(unconfigured);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "   ", "\t"})
    @DisplayName("blank model is NOT_CONFIGURED with a safe message and no client call")
    void blankModelIsNotConfiguredWithoutProviderClientCall(String model) {
        DefaultAiGateway unconfigured = new DefaultAiGateway(providerClients, PROVIDER, model, API_KEY,
                15000L, MAX_INPUT, MAX_CONTEXT);

        assertNotConfigured(unconfigured);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "   ", "\t"})
    @DisplayName("blank api key is NOT_CONFIGURED with a safe message and no client call")
    void blankApiKeyIsNotConfiguredWithoutProviderClientCall(String apiKey) {
        DefaultAiGateway unconfigured = new DefaultAiGateway(providerClients, PROVIDER, MODEL, apiKey,
                15000L, MAX_INPUT, MAX_CONTEXT);

        assertNotConfigured(unconfigured);
    }

    @Test
    @DisplayName("configured gateway without an adapter bean is UNAVAILABLE and never fabricates content")
    void configuredWithoutClientBeanIsUnavailableAndReturnsNoFakeContent() {
        AiGenerationRequest request = validRequest();

        assertThatThrownBy(() -> gateway.generate(request))
                .isInstanceOf(AiGatewayException.class)
                .satisfies(error -> {
                    assertThat(((AiGatewayException) error).getErrorCode())
                            .isEqualTo(AiGatewayErrorCode.UNAVAILABLE);
                    assertThat(error.getMessage()).isEqualTo("AI provider adapter is not available");
                    assertThat(error.getMessage()).doesNotContain(API_KEY, "input", "context");
                });

        verify(providerClients).getIfAvailable();
        verifyNoInteractions(client);
    }

    @Test
    @DisplayName("ambiguous or failing provider lookup maps to UNAVAILABLE")
    void failingProviderLookupMapsToUnavailable() {
        when(providerClients.getIfAvailable()).thenThrow(new IllegalStateException("multiple adapters"));

        AiGenerationRequest request = validRequest();
        assertThatThrownBy(() -> gateway.generate(request))
                .isInstanceOf(AiGatewayException.class)
                .satisfies(error -> {
                    assertThat(((AiGatewayException) error).getErrorCode())
                            .isEqualTo(AiGatewayErrorCode.UNAVAILABLE);
                    assertThat(error.getMessage()).doesNotContain("multiple adapters");
                });

        verifyNoInteractions(client);
    }

    @Test
    @DisplayName("approved feature identifiers are the agreed AI-003 and AI-005 values")
    void approvedFeatureIdentifiersAreExposed() {
        assertThat(AiFeature.LISTING_DESCRIPTION_ASSISTANT.getFeatureId()).isEqualTo("AI-003");
        assertThat(AiFeature.DISPUTE_SUMMARY.getFeatureId()).isEqualTo("AI-005");
    }

    private void assertNotConfigured(DefaultAiGateway candidate) {
        assertThatThrownBy(() -> candidate.generate(validRequest()))
                .isInstanceOf(AiGatewayException.class)
                .satisfies(error -> {
                    assertThat(((AiGatewayException) error).getErrorCode())
                            .isEqualTo(AiGatewayErrorCode.NOT_CONFIGURED);
                    assertThat(error.getMessage()).isEqualTo("AI generation is not available");
                    assertThat(error.getMessage()).doesNotContain(PROVIDER, MODEL, API_KEY);
                });

        verifyNoInteractions(providerClients, client);
    }

    private static AiGenerationRequest validRequest() {
        return new AiGenerationRequest(AiFeature.LISTING_DESCRIPTION_ASSISTANT,
                "input", "context", AiTone.CONCISE, AiLength.SHORT);
    }
}
