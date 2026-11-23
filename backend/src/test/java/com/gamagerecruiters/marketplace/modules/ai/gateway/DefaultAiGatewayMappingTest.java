package com.gamagerecruiters.marketplace.modules.ai.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DefaultAiGatewayMappingTest {

    private static final String API_KEY = "sk-leak-sensitive-9876543210";
    private static final String PROMPT_SECRET = "Customer prompt secret: purple-unicorn";
    private static final String CONTEXT_SECRET = "Context secret: blue-mountain";

    @Mock
    private ObjectProvider<AiProviderClient> providerClients;
    @Mock
    private AiProviderClient client;

    private DefaultAiGateway gateway;
    private ListAppender<ILoggingEvent> logs;
    private Logger gatewayLogger;

    @BeforeEach
    void setUp() {
        gateway = new DefaultAiGateway(providerClients, "approved-provider", "approved-model", API_KEY,
                15000L, 4000, 8000);
        when(providerClients.getIfAvailable()).thenReturn(client);
        gatewayLogger = (Logger) LoggerFactory.getLogger(DefaultAiGateway.class);
        logs = new ListAppender<>();
        logs.start();
        gatewayLogger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        gatewayLogger.detachAppender(logs);
    }

    @Test
    @DisplayName("mocked client success maps content, feature, provider and model and sends no key")
    void mockedClientSuccessMapsAllFields() {
        when(client.generate(any(AiProviderRequest.class)))
                .thenReturn(new AiProviderResult("Generated description", "provider-x", "model-y"));

        AiGenerationResult result = gateway.generate(listingRequest());

        assertThat(result.content()).isEqualTo("Generated description");
        assertThat(result.feature()).isEqualTo(AiFeature.LISTING_DESCRIPTION_ASSISTANT);
        assertThat(result.provider()).isEqualTo("approved-provider");
        assertThat(result.model()).isEqualTo("approved-model");
        assertThat(result.toString()).doesNotContain(API_KEY, PROMPT_SECRET, CONTEXT_SECRET);

        ArgumentCaptor<AiProviderRequest> captor = ArgumentCaptor.forClass(AiProviderRequest.class);
        verify(client).generate(captor.capture());
        AiProviderRequest sent = captor.getValue();
        assertThat(sent.featureId()).isEqualTo("AI-003");
        assertThat(sent.input()).isEqualTo(PROMPT_SECRET);
        assertThat(sent.context()).isEqualTo(CONTEXT_SECRET);
        assertThat(sent.tone()).isEqualTo(AiTone.PERSUASIVE);
        assertThat(sent.length()).isEqualTo(AiLength.LONG);
        assertThat(sent.model()).isEqualTo("approved-model");
        assertThat(sent.timeoutMs()).isEqualTo(15000L);
        assertThat(sent.toString()).doesNotContain(API_KEY);
    }

    @Test
    @DisplayName("dispute summary success echoes DISPUTE_SUMMARY and AI-005")
    void disputeSummarySuccessMapsFeature() {
        when(client.generate(any(AiProviderRequest.class)))
                .thenReturn(new AiProviderResult("Summary", "provider-x", "model-y"));

        AiGenerationResult result = gateway.generate(new AiGenerationRequest(AiFeature.DISPUTE_SUMMARY,
                PROMPT_SECRET, CONTEXT_SECRET, AiTone.CONCISE, AiLength.SHORT));

        assertThat(result.feature()).isEqualTo(AiFeature.DISPUTE_SUMMARY);
        ArgumentCaptor<AiProviderRequest> captor = ArgumentCaptor.forClass(AiProviderRequest.class);
        verify(client).generate(captor.capture());
        assertThat(captor.getValue().featureId()).isEqualTo("AI-005");
    }

    @Test
    @DisplayName("provider timeout maps to TIMEOUT with a sanitized message")
    void timeoutExceptionMapsToSanitizedTimeoutError() {
        when(client.generate(any(AiProviderRequest.class))).thenThrow(
                new ProviderTimeoutException("raw body " + API_KEY + " " + PROMPT_SECRET));

        AiGatewayException error = generateFailure();

        assertThat(error).isNotNull();
        assertThat(error.getErrorCode()).isEqualTo(AiGatewayErrorCode.TIMEOUT);
        assertThat(error.getMessage()).isEqualTo("AI generation timed out");
        assertThat(error.getMessage()).doesNotContain(API_KEY, PROMPT_SECRET, CONTEXT_SECRET, "raw body");
    }

    @Test
    @DisplayName("provider rate limit maps to RATE_LIMITED with a sanitized message")
    void rateLimitExceptionMapsToSanitizedRateLimitedError() {
        when(client.generate(any(AiProviderRequest.class))).thenThrow(
                new ProviderRateLimitException("429 " + API_KEY + " " + PROMPT_SECRET));

        AiGatewayException error = generateFailure();

        assertThat(error).isNotNull();
        assertThat(error.getErrorCode()).isEqualTo(AiGatewayErrorCode.RATE_LIMITED);
        assertThat(error.getMessage()).isEqualTo("AI generation is temporarily rate limited");
        assertThat(error.getMessage()).doesNotContain(API_KEY, PROMPT_SECRET, CONTEXT_SECRET, "429");
    }

    @Test
    @DisplayName("incidental 429 digits in a provider message do not map to RATE_LIMITED")
    void incidentalRateLimitDigitsMapToProviderError() {
        when(client.generate(any(AiProviderRequest.class))).thenThrow(
                new IllegalStateException("request id 4291 " + API_KEY));

        AiGatewayException error = generateFailure();

        assertThat(error).isNotNull();
        assertThat(error.getErrorCode()).isEqualTo(AiGatewayErrorCode.PROVIDER_ERROR);
        assertThat(error.getMessage()).isEqualTo("AI generation failed");
    }

    @Test
    @DisplayName("generic provider failure maps to PROVIDER_ERROR with a sanitized message")
    void genericProviderExceptionMapsToSanitizedProviderError() {
        when(client.generate(any(AiProviderRequest.class))).thenThrow(
                new IllegalStateException("raw provider body " + API_KEY + " " + PROMPT_SECRET));

        AiGatewayException error = generateFailure();

        assertThat(error).isNotNull();
        assertThat(error.getErrorCode()).isEqualTo(AiGatewayErrorCode.PROVIDER_ERROR);
        assertThat(error.getMessage()).isEqualTo("AI generation failed");
        assertThat(error.getMessage()).doesNotContain(API_KEY, PROMPT_SECRET, CONTEXT_SECRET, "raw provider body");
    }

    @Test
    @DisplayName("wrapped provider failures are classified from their cause")
    void wrappedExceptionsAreClassifiedFromTheirCause() {
        when(client.generate(any(AiProviderRequest.class))).thenThrow(
                new IllegalStateException("wrapper", new ProviderTimeoutException("leaked " + API_KEY)));

        AiGatewayException error = generateFailure();

        assertThat(error).isNotNull();
        assertThat(error.getErrorCode()).isEqualTo(AiGatewayErrorCode.TIMEOUT);
        assertThat(error.getMessage()).doesNotContain(API_KEY, "leaked");
    }

    @Test
    @DisplayName("no credential, prompt or context leaks into DTOs, results or error messages")
    void noKeyOrPromptLeaksIntoDtosOrMessages() {
        when(client.generate(any(AiProviderRequest.class)))
                .thenReturn(new AiProviderResult("safe", "provider-x", "model-y"));
        AiGenerationResult result = gateway.generate(listingRequest());
        ArgumentCaptor<AiProviderRequest> captor = ArgumentCaptor.forClass(AiProviderRequest.class);
        verify(client).generate(captor.capture());

        String leakedDetail = API_KEY + " " + PROMPT_SECRET + " " + CONTEXT_SECRET;
        when(client.generate(any(AiProviderRequest.class)))
                .thenThrow(new IllegalStateException("leaked " + leakedDetail));
        AiGatewayException error = generateFailure();

        assertThat(error).isNotNull();
        assertThat(error.getCause()).isNull();
        assertThat(captor.getValue().toString()).doesNotContain(API_KEY);
        assertThat(result.toString()).doesNotContain(API_KEY, PROMPT_SECRET, CONTEXT_SECRET);
        assertThat(error.getMessage()).doesNotContain(API_KEY, PROMPT_SECRET, CONTEXT_SECRET);
    }

    @Test
    @DisplayName("logs carry only feature, provider, model, duration and outcome metadata")
    void metadataLogsContainNoSensitiveValues() {
        when(client.generate(any(AiProviderRequest.class)))
                .thenReturn(new AiProviderResult("safe", "provider-z", "model-z"));
        gateway.generate(listingRequest());

        when(client.generate(any(AiProviderRequest.class))).thenThrow(
                new IllegalStateException("leaked " + API_KEY + " " + PROMPT_SECRET));
        generateFailure();

        List<String> messages = logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(messages).hasSize(2);
        for (String message : messages) {
            assertThat(message).contains("featureId=", "provider=", "model=", "durationMs=", "outcome=");
            assertThat(message).doesNotContain(API_KEY, PROMPT_SECRET, CONTEXT_SECRET, "leaked");
        }
        assertThat(messages.get(0)).contains("AI-003", "SUCCESS");
        assertThat(messages.get(1)).contains("PROVIDER_ERROR");
    }

    private AiGatewayException generateFailure() {
        Throwable thrown = catchThrowable(() -> gateway.generate(listingRequest()));
        assertThat(thrown).isInstanceOf(AiGatewayException.class);
        return (AiGatewayException) thrown;
    }

    private static AiGenerationRequest listingRequest() {
        return new AiGenerationRequest(AiFeature.LISTING_DESCRIPTION_ASSISTANT,
                PROMPT_SECRET, CONTEXT_SECRET, AiTone.PERSUASIVE, AiLength.LONG);
    }

    private static class ProviderTimeoutException extends RuntimeException {
        ProviderTimeoutException(String message) {
            super(message);
        }
    }

    private static class ProviderRateLimitException extends RuntimeException {
        ProviderRateLimitException(String message) {
            super(message);
        }
    }
}
