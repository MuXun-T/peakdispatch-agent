package com.peakdispatch.api.system;

public record ApiResponse<T>(T data, String requestId) {
    public ApiResponse(T data) {
        this(data, RequestContext.requestId());
    }
}
