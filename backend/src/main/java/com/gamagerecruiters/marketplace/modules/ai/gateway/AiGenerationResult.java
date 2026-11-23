package com.gamagerecruiters.marketplace.modules.ai.gateway;

public record AiGenerationResult(
        String content,
        AiFeature feature,
        String provider,
        String model) {
}
