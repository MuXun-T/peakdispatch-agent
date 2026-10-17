package com.peakdispatch.config;

import com.peakdispatch.application.security.SensitiveDataRedactor;
import com.fasterxml.jackson.core.JsonStreamContext;
import net.logstash.logback.mask.ValueMasker;

public final class SensitiveLogValueMasker implements ValueMasker {
    @Override
    public Object mask(JsonStreamContext context, Object value) {
        if (value instanceof CharSequence text) return SensitiveDataRedactor.redact(text.toString());
        return value;
    }
}
