package com.fintech.candles.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/**
 * Standardized error response for API errors.
 * Follows RFC 7807 Problem Details for HTTP APIs.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Error response with details about the failure")
public record ErrorResponse(
    
    @Schema(description = "HTTP status code", example = "400")
    int status,
    
    @Schema(description = "Error type/category", example = "VALIDATION_ERROR")
    String error,
    
    @Schema(description = "Human-readable error message", example = "Invalid request parameters")
    String message,
    
    @Schema(description = "Request path that caused the error", example = "/api/v1/history")
    String path,
    
    @Schema(description = "Timestamp of the error", example = "2025-12-09T10:30:00Z")
    Instant timestamp,
    
    @Schema(description = "Detailed validation errors (if applicable)")
    List<ValidationError> validationErrors
) {
    
    public ErrorResponse(int status, String error, String message, String path) {
        this(status, error, message, path, Instant.now(), null);
    }
    
    public ErrorResponse(int status, String error, String message, String path, List<ValidationError> validationErrors) {
        this(status, error, message, path, Instant.now(), validationErrors);
    }
    
    /**
     * Individual field validation error.
     */
    @Schema(description = "Field-level validation error")
    public record ValidationError(
        @Schema(description = "Field name that failed validation", example = "interval")
        String field,
        
        @Schema(description = "Rejected value", example = "99s")
        String rejectedValue,
        
        @Schema(description = "Validation error message", example = "Unsupported interval. Must be one of: 1s, 5s, 1m, 15m, 1h")
        String message
    ) {}
}
