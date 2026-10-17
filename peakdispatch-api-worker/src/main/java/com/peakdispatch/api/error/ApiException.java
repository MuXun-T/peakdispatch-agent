package com.peakdispatch.api.error;

import java.util.Map;
import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {
    private final String code;
    private final HttpStatus status;
    private final boolean retryable;
    private final Map<String, Object> details;

    public ApiException(String code, String message, HttpStatus status, boolean retryable) {
        this(code, message, status, retryable, Map.of());
    }

    public ApiException(String code, String message, HttpStatus status, boolean retryable,
                        Map<String, Object> details) {
        super(message);
        this.code = code;
        this.status = status;
        this.retryable = retryable;
        this.details = Map.copyOf(details);
    }

    public String code() {
        return code;
    }

    public HttpStatus status() {
        return status;
    }

    public boolean retryable() {
        return retryable;
    }

    public Map<String, Object> details() {
        return details;
    }
}
