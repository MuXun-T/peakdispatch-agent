package com.peakdispatch.api.error;

import java.util.Map;

public record ApiError(String code, String message, boolean retryable, Map<String, Object> details) {
}
