package com.fintech.candles.api;

import com.fintech.candles.service.CandleService;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Global exception handler for REST API controllers.
 * Provides consistent error responses across all endpoints.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {
    
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    
    /**
     * Handle service layer validation errors.
     */
    @ExceptionHandler(CandleService.ValidationException.class)
    public ResponseEntity<ErrorResponse> handleServiceValidation(
            CandleService.ValidationException ex,
            WebRequest request) {
        
        String path = request.getDescription(false).replace("uri=", "");
        ErrorResponse error = new ErrorResponse(
            HttpStatus.BAD_REQUEST.value(),
            "SERVICE_VALIDATION_ERROR",
            ex.getMessage(),
            path
        );
        
        log.warn("Service validation error on {}: {}", path, ex.getMessage());
        return ResponseEntity.badRequest().body(error);
    }
    
    /**
     * Handle service layer errors (infrastructure failures).
     */
    @ExceptionHandler(CandleService.ServiceException.class)
    public ResponseEntity<ErrorResponse> handleServiceException(
            CandleService.ServiceException ex,
            WebRequest request) {
        
        String path = request.getDescription(false).replace("uri=", "");
        ErrorResponse error = new ErrorResponse(
            HttpStatus.INTERNAL_SERVER_ERROR.value(),
            "SERVICE_ERROR",
            "A service error occurred. Our team has been notified.",
            path
        );
        
        log.error("Service exception on {}: {}", path, ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    }
    
    /**
     * Handle validation constraint violations.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(
            ConstraintViolationException ex, 
            WebRequest request) {
        
        List<ErrorResponse.ValidationError> validationErrors = ex.getConstraintViolations().stream()
            .map(violation -> new ErrorResponse.ValidationError(
                getFieldName(violation),
                violation.getInvalidValue() != null ? violation.getInvalidValue().toString() : "null",
                violation.getMessage()
            ))
            .collect(Collectors.toList());
        
        String path = request.getDescription(false).replace("uri=", "");
        ErrorResponse error = new ErrorResponse(
            HttpStatus.BAD_REQUEST.value(),
            "VALIDATION_ERROR",
            "Request validation failed",
            path,
            validationErrors
        );
        
        log.warn("Validation error on {}: {}", path, validationErrors);
        return ResponseEntity.badRequest().body(error);
    }
    
    /**
     * Handle missing required parameters.
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParameter(
            MissingServletRequestParameterException ex,
            WebRequest request) {
        
        String path = request.getDescription(false).replace("uri=", "");
        ErrorResponse error = new ErrorResponse(
            HttpStatus.BAD_REQUEST.value(),
            "MISSING_PARAMETER",
            String.format("Required parameter '%s' is missing", ex.getParameterName()),
            path,
            List.of(new ErrorResponse.ValidationError(
                ex.getParameterName(),
                null,
                "This parameter is required"
            ))
        );
        
        log.warn("Missing parameter on {}: {}", path, ex.getParameterName());
        return ResponseEntity.badRequest().body(error);
    }
    
    /**
     * Handle type conversion errors (e.g., string instead of number).
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex,
            WebRequest request) {
        
        String path = request.getDescription(false).replace("uri=", "");
        String expectedType = ex.getRequiredType() != null ? ex.getRequiredType().getSimpleName() : "unknown";
        
        ErrorResponse error = new ErrorResponse(
            HttpStatus.BAD_REQUEST.value(),
            "TYPE_MISMATCH",
            String.format("Parameter '%s' must be a valid %s", ex.getName(), expectedType),
            path,
            List.of(new ErrorResponse.ValidationError(
                ex.getName(),
                ex.getValue() != null ? ex.getValue().toString() : "null",
                String.format("Expected type: %s", expectedType)
            ))
        );
        
        log.warn("Type mismatch on {}: {} expected {} but got {}", 
                path, ex.getName(), expectedType, ex.getValue());
        return ResponseEntity.badRequest().body(error);
    }
    
    /**
     * Handle illegal argument exceptions (e.g., invalid interval).
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(
            IllegalArgumentException ex,
            WebRequest request) {
        
        String path = request.getDescription(false).replace("uri=", "");
        ErrorResponse error = new ErrorResponse(
            HttpStatus.BAD_REQUEST.value(),
            "INVALID_ARGUMENT",
            ex.getMessage(),
            path
        );
        
        log.warn("Invalid argument on {}: {}", path, ex.getMessage());
        return ResponseEntity.badRequest().body(error);
    }
    
    /**
     * Handle all other unexpected exceptions.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(
            Exception ex,
            WebRequest request) {
        
        String path = request.getDescription(false).replace("uri=", "");
        ErrorResponse error = new ErrorResponse(
            HttpStatus.INTERNAL_SERVER_ERROR.value(),
            "INTERNAL_ERROR",
            "An unexpected error occurred. Please contact support if this persists.",
            path
        );
        
        log.error("Unexpected error on {}: {}", path, ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    }
    
    /**
     * Extract field name from constraint violation.
     */
    private String getFieldName(ConstraintViolation<?> violation) {
        String propertyPath = violation.getPropertyPath().toString();
        int lastDot = propertyPath.lastIndexOf('.');
        return lastDot >= 0 ? propertyPath.substring(lastDot + 1) : propertyPath;
    }
}
