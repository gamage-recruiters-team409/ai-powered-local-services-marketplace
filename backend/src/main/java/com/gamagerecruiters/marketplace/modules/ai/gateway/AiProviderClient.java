package com.gamagerecruiters.marketplace.modules.ai.gateway;

interface AiProviderClient {

    AiProviderResult generate(AiProviderRequest request);
}
