package com.gamagerecruiters.marketplace.modules.ai.gateway;

public enum AiFeature {

    LISTING_DESCRIPTION_ASSISTANT("AI-003"),
    DISPUTE_SUMMARY("AI-005");

    private final String featureId;

    AiFeature(String featureId) {
        this.featureId = featureId;
    }

    public String getFeatureId() {
        return featureId;
    }

    public String featureId() {
        return featureId;
    }
}
