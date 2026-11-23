package com.gamagerecruiters.marketplace.modules.ai.gateway;

record AiProviderRequest(
        String featureId,
        String input,
        String context,
        AiTone tone,
        AiLength length,
        String model,
        long timeoutMs) {
}
