package com.gamagerecruiters.marketplace.modules.ai.gateway;

public class AiGatewayException extends RuntimeException {

    private final AiGatewayErrorCode errorCode;

    public AiGatewayException(AiGatewayErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public AiGatewayException(AiGatewayErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public AiGatewayErrorCode getErrorCode() {
        return errorCode;
    }
}
