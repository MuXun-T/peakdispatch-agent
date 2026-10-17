package com.peakdispatch.api.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.peakdispatch.api.error.ApiError;
import com.peakdispatch.api.error.ApiErrorResponse;
import com.peakdispatch.api.system.RequestContext;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

@Component
public class JsonSecurityErrorWriter {
    private final ObjectMapper objectMapper;

    public JsonSecurityErrorWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void write(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), new ApiErrorResponse(
                new ApiError(code, message, false, Map.of()), RequestContext.traceId()));
    }
}
