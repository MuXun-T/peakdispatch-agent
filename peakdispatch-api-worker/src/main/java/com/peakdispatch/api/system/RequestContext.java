package com.peakdispatch.api.system;

import org.slf4j.MDC;

public final class RequestContext {
    private RequestContext() {
    }

    public static String requestId() {
        return MDC.get("requestId");
    }

    public static String traceId() {
        return MDC.get("traceId");
    }
}
