package com.gamagerecruiters.marketplace.modules.ai.gateway;

public record AiGenerationRequest(
        AiFeature feature,
        String input,
        String context,
        AiTone tone,
        AiLength length) {
}
