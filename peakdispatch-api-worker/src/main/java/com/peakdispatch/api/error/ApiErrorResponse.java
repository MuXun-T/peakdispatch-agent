package com.peakdispatch.api.error;

public record ApiErrorResponse(ApiError error, String traceId) {
}
