package com.peakdispatch.api.error;

import com.peakdispatch.api.system.RequestContext;
import com.peakdispatch.application.order.MerchantUnavailableException;
import com.peakdispatch.application.order.MerchantOwnershipException;
import com.peakdispatch.application.order.OrderNotFoundException;
import com.peakdispatch.application.order.OrderVersionConflictException;
import com.peakdispatch.domain.order.InvalidOrderTransitionException;
import com.peakdispatch.application.courier.CourierNotFoundException;
import com.peakdispatch.application.courier.CourierSessionConflictException;
import com.peakdispatch.application.dispatch.DispatchOfferConflictException;
import com.peakdispatch.application.dispatch.DispatchOfferNotFoundException;
import com.peakdispatch.application.order.PreparationEventConflictException;
import com.peakdispatch.application.agent.AgentAccessDeniedException;
import com.peakdispatch.application.agent.AgentConcurrencyLimitException;
import com.peakdispatch.application.agent.AgentConcurrencyUnavailableException;
import com.peakdispatch.application.agent.AgentConflictException;
import com.peakdispatch.application.agent.AgentNotFoundException;
import com.peakdispatch.application.security.SensitiveDataRedactor;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.jdbc.core.JdbcTemplate;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

@RestControllerAdvice
public class ApiExceptionHandler {
    private final JdbcTemplate jdbc;

    public ApiExceptionHandler(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiErrorResponse> handleApiException(ApiException exception) {
        return response(exception.status(), exception.code(), exception.getMessage(),
                exception.retryable(), exception.details());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException exception) {
        Map<String, Object> errors = exception.getBindingResult().getFieldErrors().stream()
                .collect(java.util.stream.Collectors.toMap(
                        FieldError::getField,
                        error -> error.getDefaultMessage() == null ? "invalid value" : error.getDefaultMessage(),
                        (first, ignored) -> first));
        return response(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Request validation failed", false, errors);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiErrorResponse> handleUnreadable(HttpMessageNotReadableException exception) {
        return response(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Request body is invalid", false, Map.of());
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiErrorResponse> handleAccessDenied(AccessDeniedException exception,
                                                        HttpServletRequest request,
                                                        Authentication authentication) {
        String path = request.getRequestURI();
        if (path.startsWith("/api/v1/ops/") || path.startsWith("/api/v1/dispatch/policies")
                || path.startsWith("/api/v1/knowledge/")) {
            try {
                UUID auditId = UUID.randomUUID();
                jdbc.update("""
                        INSERT INTO audit_log (id, actor_id, action, target_type, target_id, decision,
                            trace_id, details_redacted, created_at)
                        VALUES (?, ?, 'OPS_ACCESS_DENIED', 'HTTP_ENDPOINT', ?, 'DENIED', ?, ?, ?)
                        """, ByteBuffer.allocate(16).putLong(auditId.getMostSignificantBits())
                        .putLong(auditId.getLeastSignificantBits()).array(),
                        authentication == null ? "anonymous" : authentication.getName(), path,
                        RequestContext.traceId(), SensitiveDataRedactor.redact("method=" + request.getMethod()),
                        Timestamp.from(Instant.now()));
            } catch (RuntimeException ignored) {
                // Preserve the authorization response if the audit store itself is unavailable.
            }
        }
        return response(HttpStatus.FORBIDDEN, "FORBIDDEN", "The operation is not permitted", false, Map.of());
    }

    @ExceptionHandler(AgentAccessDeniedException.class)
    ResponseEntity<ApiErrorResponse> handleAgentAccessDenied(AgentAccessDeniedException exception) {
        return response(HttpStatus.FORBIDDEN, "FORBIDDEN", "The operation is not permitted", false, Map.of());
    }

    @ExceptionHandler(AgentConflictException.class)
    ResponseEntity<ApiErrorResponse> handleAgentConflict(AgentConflictException exception) {
        return response(HttpStatus.CONFLICT, "AGENT_CONFLICT", exception.getMessage(), false, Map.of());
    }

    @ExceptionHandler(AgentConcurrencyLimitException.class)
    ResponseEntity<ApiErrorResponse> handleAgentConcurrencyLimit(AgentConcurrencyLimitException exception) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(exception.retryAfterSeconds()))
                .body(new ApiErrorResponse(new ApiError(exception.code(), "Agent concurrency limit exceeded",
                        true, Map.of()), RequestContext.traceId()));
    }

    @ExceptionHandler(AgentConcurrencyUnavailableException.class)
    ResponseEntity<ApiErrorResponse> handleAgentConcurrencyUnavailable(AgentConcurrencyUnavailableException exception) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(new ApiErrorResponse(new ApiError("RATE_LIMIT_BACKEND_UNAVAILABLE",
                        "Concurrency control is unavailable", true, Map.of()), RequestContext.traceId()));
    }

    @ExceptionHandler(AgentNotFoundException.class)
    ResponseEntity<ApiErrorResponse> handleAgentNotFound(AgentNotFoundException exception) {
        return response(HttpStatus.NOT_FOUND, "AGENT_RESOURCE_NOT_FOUND", exception.getMessage(), false, Map.of());
    }

    @ExceptionHandler(OrderNotFoundException.class)
    ResponseEntity<ApiErrorResponse> handleOrderNotFound(OrderNotFoundException exception) {
        return response(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "Order was not found", false, Map.of());
    }

    @ExceptionHandler(MerchantUnavailableException.class)
    ResponseEntity<ApiErrorResponse> handleMerchantUnavailable(MerchantUnavailableException exception) {
        return response(HttpStatus.UNPROCESSABLE_ENTITY, "MERCHANT_UNAVAILABLE",
                "Merchant is not active in the requested region", false,
                Map.of("merchantId", exception.merchantId(), "regionId", exception.regionId()));
    }

    @ExceptionHandler(MerchantOwnershipException.class)
    ResponseEntity<ApiErrorResponse> handleMerchantOwnership(MerchantOwnershipException exception) {
        return response(HttpStatus.FORBIDDEN, "MERCHANT_ORDER_SCOPE_DENIED",
                "The merchant is not allowed to update this order", false,
                Map.of("merchantId", exception.merchantId(), "orderId", exception.orderId()));
    }

    @ExceptionHandler(OrderVersionConflictException.class)
    ResponseEntity<ApiErrorResponse> handleOrderVersionConflict(OrderVersionConflictException exception) {
        return response(HttpStatus.CONFLICT, "VERSION_CONFLICT", "Order version or status has changed", false,
                Map.of("orderId", exception.orderId(), "currentVersion", exception.currentVersion(),
                        "currentStatus", exception.currentStatus().name()));
    }

    @ExceptionHandler(InvalidOrderTransitionException.class)
    ResponseEntity<ApiErrorResponse> handleInvalidTransition(InvalidOrderTransitionException exception) {
        return response(HttpStatus.CONFLICT, "INVALID_TRANSITION", exception.getMessage(), false,
                Map.of("from", exception.from().name(), "to", exception.to().name()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiErrorResponse> handleIllegalArgument(IllegalArgumentException exception) {
        return response(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", exception.getMessage(), false, Map.of());
    }

    @ExceptionHandler(CourierNotFoundException.class)
    ResponseEntity<ApiErrorResponse> handleCourierNotFound(CourierNotFoundException exception) {
        return response(HttpStatus.NOT_FOUND, "COURIER_NOT_FOUND", "Courier was not found", false, Map.of());
    }

    @ExceptionHandler(CourierSessionConflictException.class)
    ResponseEntity<ApiErrorResponse> handleCourierSession(CourierSessionConflictException exception) {
        return response(HttpStatus.CONFLICT, "COURIER_SESSION_CONFLICT", exception.getMessage(), false,
                Map.of("courierId", exception.courierId()));
    }

    @ExceptionHandler(DispatchOfferNotFoundException.class)
    ResponseEntity<ApiErrorResponse> handleOfferNotFound(DispatchOfferNotFoundException exception) {
        return response(HttpStatus.NOT_FOUND, "OFFER_NOT_FOUND", "Dispatch offer was not found", false, Map.of());
    }

    @ExceptionHandler(DispatchOfferConflictException.class)
    ResponseEntity<ApiErrorResponse> handleOfferConflict(DispatchOfferConflictException exception) {
        return response(HttpStatus.CONFLICT, "OFFER_CONFLICT", exception.getMessage(), false, Map.of());
    }

    @ExceptionHandler(PreparationEventConflictException.class)
    ResponseEntity<ApiErrorResponse> handlePreparationEventConflict(PreparationEventConflictException exception) {
        return response(HttpStatus.CONFLICT, "EVENT_ID_REUSED", exception.getMessage(), false, Map.of());
    }

    @ExceptionHandler(com.peakdispatch.application.dispatch.RedisUnavailableException.class)
    ResponseEntity<ApiErrorResponse> handleRedisUnavailable(com.peakdispatch.application.dispatch.RedisUnavailableException exception) {
        return response(HttpStatus.SERVICE_UNAVAILABLE, "REDIS_UNAVAILABLE", exception.getMessage(), true, Map.of());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiErrorResponse> handleUnexpected(Exception exception) {
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "An unexpected error occurred",
                true, Map.of());
    }

    static ResponseEntity<ApiErrorResponse> response(HttpStatus status, String code, String message,
                                                      boolean retryable, Map<String, Object> details) {
        ApiError error = new ApiError(code, message, retryable, Map.copyOf(details));
        return ResponseEntity.status(status).body(new ApiErrorResponse(error, RequestContext.traceId()));
    }
}
