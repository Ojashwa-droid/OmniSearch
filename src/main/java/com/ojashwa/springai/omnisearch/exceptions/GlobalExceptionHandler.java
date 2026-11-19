package com.ojashwa.springai.omnisearch.exceptions;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import jakarta.servlet.http.HttpServletRequest;

import java.util.Map;



/**
 * Global exception handler for the Omnisearch application.
 * <p>
 * This class uses {@link ControllerAdvice} to handle exceptions across all controllers
 * in the application, providing consistent error responses to clients.
 * </p>
 */
@ControllerAdvice
public class GlobalExceptionHandler {
    /**
     * Handles {@link IllegalArgumentException} exceptions.
     * <p>
     * This method catches illegal argument exceptions (typically caused by invalid request parameters)
     * and returns a 400 Bad Request response with an appropriate error message.
     * </p>
     *
     * @param error the IllegalArgumentException that was thrown
     * @return a ResponseEntity with HTTP 400 status and an error message
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> handleInvalidRequest(IllegalArgumentException error, HttpServletRequest request) {
        String message = error.getMessage() == null ? "The request could not be processed." : error.getMessage();
        return errorResponse(HttpStatus.BAD_REQUEST, message, request);
    }

    /**
     * Handles {@link IllegalStateException} exceptions.
     * <p>
     * This method catches illegal state exceptions (typically caused by unavailable services or invalid state)
     * and returns a 503 Service Unavailable response with an appropriate error message.
     * </p>
     *
     * @param error the IllegalStateException that was thrown
     * @return a ResponseEntity with HTTP 503 status and an error message
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<?> handleUnavailableProvider(IllegalStateException error, HttpServletRequest request) {
        String message = error.getMessage() == null ? "A required service is unavailable." : error.getMessage();
        return errorResponse(HttpStatus.SERVICE_UNAVAILABLE, message, request);
    }

    private ResponseEntity<?> errorResponse(HttpStatus status, String message, HttpServletRequest request) {
        String accept = request.getHeader("Accept");
        if (accept != null && accept.contains(MediaType.TEXT_EVENT_STREAM_VALUE)) {
            String safeMessage = message.replaceAll("[\\r\\n]+", " ");
            return ResponseEntity.status(status)
                    .contentType(MediaType.TEXT_EVENT_STREAM)
                    .body("data: " + safeMessage + "\n\n");
        }
        return ResponseEntity.status(status).body(Map.of("message", message));
    }
}
