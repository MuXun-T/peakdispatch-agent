package com.peakdispatch.application.dispatch;

/** Returned when dispatch cannot safely establish its external capacity lease. */
public class RedisUnavailableException extends RuntimeException {
    public RedisUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public RedisUnavailableException(String message) {
        super(message);
    }
}
