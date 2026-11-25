package com.gamagerecruiters.marketplace.modules.ai.gateway;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class DefaultAiGateway implements AiGateway {

    private static final Logger log = LoggerFactory.getLogger(DefaultAiGateway.class);

    private static final Set<AiFeature> ALLOWED_FEATURES = Set.of(
            AiFeature.LISTING_DESCRIPTION_ASSISTANT,
            AiFeature.DISPUTE_SUMMARY);
    private static final Pattern RATE_LIMIT_STATUS = Pattern.compile("(^|\\D)429(\\D|$)");

    private static final String INVALID_REQUEST_MESSAGE = "AI generation request is invalid";
    private static final String FEATURE_NOT_ALLOWED_MESSAGE = "AI feature is not allowed";
    private static final String NOT_CONFIGURED_MESSAGE = "AI generation is not available";
    private static final String UNAVAILABLE_MESSAGE = "AI provider adapter is not available";
    private static final String TIMEOUT_MESSAGE = "AI generation timed out";
    private static final String RATE_LIMITED_MESSAGE = "AI generation is temporarily rate limited";
    private static final String PROVIDER_ERROR_MESSAGE = "AI generation failed";

    private final ObjectProvider<AiProviderClient> providerClients;
    private final String provider;
    private final String model;
    private final String apiKey;
    private final long requestTimeoutMs;
    private final int maxInputCharacters;
    private final int maxContextCharacters;

    @Autowired
    public DefaultAiGateway(
            ObjectProvider<AiProviderClient> providerClients,
            @Value("${app.ai.provider:}") String provider,
            @Value("${app.ai.model:}") String model,
            @Value("${app.ai.api-key:}") String apiKey,
            @Value("${app.ai.request-timeout-ms:15000}") long requestTimeoutMs,
            @Value("${app.ai.max-input-characters:4000}") int maxInputCharacters,
            @Value("${app.ai.max-context-characters:8000}") int maxContextCharacters) {
        this.providerClients = providerClients;
        this.provider = provider;
        this.model = model;
        this.apiKey = apiKey;
        this.requestTimeoutMs = requestTimeoutMs;
        this.maxInputCharacters = maxInputCharacters;
        this.maxContextCharacters = maxContextCharacters;
    }

    @Override
    public AiGenerationResult generate(AiGenerationRequest request) {
        long startedAt = System.currentTimeMillis();
        validate(request);

        if (isBlank(provider) || isBlank(model) || isBlank(apiKey)) {
            throw new AiGatewayException(AiGatewayErrorCode.NOT_CONFIGURED, NOT_CONFIGURED_MESSAGE);
        }

        String featureId = request.feature().getFeatureId();
        AiProviderClient client;
        try {
            client = providerClients.getIfAvailable();
        } catch (RuntimeException failure) {
            log.warn("AI generation skipped featureId={} provider={} model={} durationMs={} outcome={}",
                    featureId, provider, model,
                    System.currentTimeMillis() - startedAt, AiGatewayErrorCode.UNAVAILABLE.name());
            throw new AiGatewayException(AiGatewayErrorCode.UNAVAILABLE, UNAVAILABLE_MESSAGE);
        }
        if (client == null) {
            log.warn("AI generation skipped featureId={} provider={} model={} durationMs={} outcome={}",
                    featureId, provider, model,
                    System.currentTimeMillis() - startedAt, AiGatewayErrorCode.UNAVAILABLE.name());
            throw new AiGatewayException(AiGatewayErrorCode.UNAVAILABLE, UNAVAILABLE_MESSAGE);
        }

        try {
            AiProviderRequest providerRequest = new AiProviderRequest(
                    featureId,
                    request.input(),
                    request.context(),
                    request.tone(),
                    request.length(),
                    model,
                    requestTimeoutMs);
            AiProviderResult providerResult = client.generate(providerRequest);
            if (providerResult == null) {
                throw new AiGatewayException(AiGatewayErrorCode.PROVIDER_ERROR, PROVIDER_ERROR_MESSAGE);
            }
            AiGenerationResult result = new AiGenerationResult(
                    providerResult.content(),
                    request.feature(),
                    provider,
                    model);
            log.info("AI generation completed featureId={} provider={} model={} durationMs={} outcome={}",
                    featureId, provider, model,
                    System.currentTimeMillis() - startedAt, "SUCCESS");
            return result;
        } catch (RuntimeException failure) {
            AiGatewayErrorCode errorCode = classify(failure);
            log.warn("AI generation failed featureId={} provider={} model={} durationMs={} outcome={}",
                    featureId, provider, model,
                    System.currentTimeMillis() - startedAt, errorCode.name());
            throw new AiGatewayException(errorCode, sanitizedMessage(errorCode));
        }
    }

    private void validate(AiGenerationRequest request) {
        if (request == null) {
            throw new AiGatewayException(AiGatewayErrorCode.INVALID_REQUEST, INVALID_REQUEST_MESSAGE);
        }
        if (request.feature() == null) {
            throw new AiGatewayException(AiGatewayErrorCode.INVALID_REQUEST, INVALID_REQUEST_MESSAGE);
        }
        if (!ALLOWED_FEATURES.contains(request.feature())) {
            throw new AiGatewayException(AiGatewayErrorCode.FEATURE_NOT_ALLOWED, FEATURE_NOT_ALLOWED_MESSAGE);
        }
        if (isBlank(request.input()) || request.input().length() > maxInputCharacters) {
            throw new AiGatewayException(AiGatewayErrorCode.INVALID_REQUEST, INVALID_REQUEST_MESSAGE);
        }
        if (isBlank(request.context()) || request.context().length() > maxContextCharacters) {
            throw new AiGatewayException(AiGatewayErrorCode.INVALID_REQUEST, INVALID_REQUEST_MESSAGE);
        }
        if (request.tone() == null || request.length() == null) {
            throw new AiGatewayException(AiGatewayErrorCode.INVALID_REQUEST, INVALID_REQUEST_MESSAGE);
        }
    }

    private AiGatewayErrorCode classify(Throwable failure) {
        Throwable current = failure;
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        while (current != null && seen.add(current)) {
            if (current instanceof AiGatewayException gatewayException) {
                return gatewayException.getErrorCode();
            }
            String type = current.getClass().getSimpleName().toLowerCase(Locale.ROOT);
            if (type.contains("timeout") || type.contains("timedout")) {
                return AiGatewayErrorCode.TIMEOUT;
            }
            if (type.contains("ratelimit") || type.contains("rate_limit") || type.contains("toomanyrequests")) {
                return AiGatewayErrorCode.RATE_LIMITED;
            }
            String detail = current.getMessage();
            if (detail != null && !detail.isBlank()) {
                String text = detail.toLowerCase(Locale.ROOT);
                if (text.contains("rate limit") || text.contains("too many requests")
                        || RATE_LIMIT_STATUS.matcher(text).find()) {
                    return AiGatewayErrorCode.RATE_LIMITED;
                }
                if (text.contains("timeout") || text.contains("timed out")) {
                    return AiGatewayErrorCode.TIMEOUT;
                }
            }
            current = current.getCause();
        }
        return AiGatewayErrorCode.PROVIDER_ERROR;
    }

    private String sanitizedMessage(AiGatewayErrorCode errorCode) {
        return switch (errorCode) {
            case INVALID_REQUEST -> INVALID_REQUEST_MESSAGE;
            case FEATURE_NOT_ALLOWED -> FEATURE_NOT_ALLOWED_MESSAGE;
            case NOT_CONFIGURED -> NOT_CONFIGURED_MESSAGE;
            case UNAVAILABLE -> UNAVAILABLE_MESSAGE;
            case TIMEOUT -> TIMEOUT_MESSAGE;
            case RATE_LIMITED -> RATE_LIMITED_MESSAGE;
            case PROVIDER_ERROR -> PROVIDER_ERROR_MESSAGE;
        };
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
