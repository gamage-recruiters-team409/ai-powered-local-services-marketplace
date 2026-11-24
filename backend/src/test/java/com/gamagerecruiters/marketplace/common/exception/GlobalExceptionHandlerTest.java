package com.gamagerecruiters.marketplace.common.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;

import com.gamagerecruiters.marketplace.common.dto.ErrorResponse;

import jakarta.servlet.http.HttpServletRequest;

class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler exceptionHandler;
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        exceptionHandler = new GlobalExceptionHandler();
        request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/api/v1/customers/me/photo");
        when(request.getMethod()).thenReturn("PUT");
    }

    @Test
    @DisplayName("handleHttpMediaTypeNotSupported returns 415 UNSUPPORTED_MEDIA_TYPE with neutral message")
    void handleHttpMediaTypeNotSupported_returns415() {
        HttpMediaTypeNotSupportedException ex = new HttpMediaTypeNotSupportedException("Content-Type 'application/json' is not supported");

        ResponseEntity<ErrorResponse> response = exceptionHandler.handleHttpMediaTypeNotSupported(ex, request);

        assertEquals(HttpStatus.UNSUPPORTED_MEDIA_TYPE, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(415, response.getBody().status());
        assertEquals("UNSUPPORTED_MEDIA_TYPE", response.getBody().error());
        assertEquals("Unsupported media type", response.getBody().message());
        assertEquals("/api/v1/customers/me/photo", response.getBody().path());
    }

    @Test
    @DisplayName("handleHttpRequestMethodNotSupported returns 405 METHOD_NOT_ALLOWED with neutral message")
    void handleHttpRequestMethodNotSupported_returns405() {
        HttpRequestMethodNotSupportedException ex = new HttpRequestMethodNotSupportedException("POST");

        ResponseEntity<ErrorResponse> response = exceptionHandler.handleHttpRequestMethodNotSupported(ex, request);

        assertEquals(HttpStatus.METHOD_NOT_ALLOWED, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(405, response.getBody().status());
        assertEquals("METHOD_NOT_ALLOWED", response.getBody().error());
        assertEquals("Request method not supported", response.getBody().message());
        assertEquals("/api/v1/customers/me/photo", response.getBody().path());
    }

    @Test
    @DisplayName("handleGenericException returns 500 INTERNAL_SERVER_ERROR and logs without sensitive data")
    void handleGenericException_returns500() {
        Exception ex = new RuntimeException("Unexpected database failure with sensitive data");

        ResponseEntity<ErrorResponse> response = exceptionHandler.handleGenericException(ex, request);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(500, response.getBody().status());
        assertEquals("INTERNAL_SERVER_ERROR", response.getBody().error());
        assertEquals("An unexpected internal error occurred", response.getBody().message());
        assertEquals("/api/v1/customers/me/photo", response.getBody().path());
    }
}
