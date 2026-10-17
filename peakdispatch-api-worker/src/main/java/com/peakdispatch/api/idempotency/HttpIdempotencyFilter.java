package com.peakdispatch.api.idempotency;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.peakdispatch.api.error.ApiError;
import com.peakdispatch.api.error.ApiErrorResponse;
import com.peakdispatch.api.system.RequestContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ReadListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

@Component
public class HttpIdempotencyFilter extends OncePerRequestFilter {
    private static final Pattern VALID_KEY = Pattern.compile("[A-Za-z0-9._:-]{8,128}");
    private static final List<String> REPLAY_HEADERS = List.of("Location", "ETag", "Cache-Control");
    private static final Duration RETENTION = Duration.ofDays(7);
    private static final int MAX_REQUEST_BODY_BYTES = 1_048_576;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public HttpIdempotencyFilter(JdbcTemplate jdbc, ObjectMapper objectMapper,
                                 PlatformTransactionManager transactionManager, Clock clock) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith(request.getContextPath() + "/api/")
                || "GET".equalsIgnoreCase(request.getMethod())
                || "HEAD".equalsIgnoreCase(request.getMethod())
                || "OPTIONS".equalsIgnoreCase(request.getMethod())
                || path.endsWith("/ops/chat");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String key = request.getHeader("Idempotency-Key");
        if (key == null || !VALID_KEY.matcher(key).matches()) {
            writeError(response, 400, "IDEMPOTENCY_KEY_REQUIRED",
                    "A valid Idempotency-Key header is required");
            return;
        }

        byte[] requestBody = request.getInputStream().readNBytes(MAX_REQUEST_BODY_BYTES + 1);
        if (requestBody.length > MAX_REQUEST_BODY_BYTES) {
            writeError(response, 413, "REQUEST_TOO_LARGE", "Request body exceeds the 1 MiB limit");
            return;
        }
        CachedRequest wrappedRequest = new CachedRequest(request, requestBody);
        ContentCachingResponseWrapper wrappedResponse = new ContentCachingResponseWrapper(response);
        String scope = scope(request);
        String keyHash = sha256(key.getBytes(StandardCharsets.UTF_8));
        String requestHash = requestHash(request, requestBody);
        CachedResponse result;
        try {
            result = transactionTemplate.execute(status -> executeOnce(
                    status, scope, keyHash, requestHash, wrappedRequest, wrappedResponse, chain));
        } catch (FilterExecutionException exception) {
            if (exception.getCause() instanceof IOException ioException) {
                throw ioException;
            }
            if (exception.getCause() instanceof ServletException servletException) {
                throw servletException;
            }
            throw exception;
        }
        if (result == null) {
            throw new IllegalStateException("idempotency transaction returned no response");
        }
        response.setStatus(result.status());
        if (result.contentType() != null) {
            response.setContentType(result.contentType());
        }
        result.headers().forEach(response::setHeader);
        response.getOutputStream().write(result.body());
    }

    private CachedResponse executeOnce(TransactionStatus tx, String scope, String keyHash, String requestHash,
                                       HttpServletRequest request, ContentCachingResponseWrapper response,
                                       FilterChain chain) {
        Instant now = Instant.now(clock);
        jdbc.update("""
                INSERT IGNORE INTO api_idempotency
                    (scope, key_hash, request_hash, status, created_at, expires_at)
                VALUES (?, ?, ?, 'PROCESSING', ?, ?)
                """, scope, keyHash, requestHash, now, now.plus(RETENTION));
        IdempotencyRow row = jdbc.queryForObject("""
                SELECT request_hash, status, response_status, response_content_type,
                       response_headers, response_body, expires_at
                FROM api_idempotency WHERE scope = ? AND key_hash = ? FOR UPDATE
                """, (rs, rowNum) -> readRow(rs), scope, keyHash);

        if (row.expiresAt() != null && !row.expiresAt().isAfter(now)) {
            jdbc.update("DELETE FROM api_idempotency WHERE scope = ? AND key_hash = ?", scope, keyHash);
            jdbc.update("""
                    INSERT INTO api_idempotency
                        (scope, key_hash, request_hash, status, created_at, expires_at)
                    VALUES (?, ?, ?, 'PROCESSING', ?, ?)
                    """, scope, keyHash, requestHash, now, now.plus(RETENTION));
            row = new IdempotencyRow(requestHash, "PROCESSING", null, null, Map.of(), null, now.plus(RETENTION));
        }

        if (!row.requestHash().equals(requestHash)) {
            return errorResponse(409, "IDEMPOTENCY_KEY_REUSED",
                    "This Idempotency-Key was already used with a different request");
        }
        if ("COMPLETED".equals(row.status())) {
            return new CachedResponse(row.responseStatus(), row.responseContentType(), row.headers(), row.body());
        }

        try {
            chain.doFilter(request, response);
        } catch (IOException | ServletException exception) {
            throw new FilterExecutionException(exception);
        }

        int responseStatus = response.getStatus();
        byte[] body = response.getContentAsByteArray();
        String contentType = response.getContentType();
        Map<String, String> headers = new LinkedHashMap<>();
        REPLAY_HEADERS.forEach(name -> {
            String value = response.getHeader(name);
            if (value != null) {
                headers.put(name, value);
            }
        });
        if (responseStatus >= 500 || tx.isRollbackOnly()) {
            tx.setRollbackOnly();
        } else {
            try {
                jdbc.update("""
                        UPDATE api_idempotency
                        SET status = 'COMPLETED', response_status = ?, response_content_type = ?,
                            response_headers = ?, response_body = ?
                        WHERE scope = ? AND key_hash = ?
                        """, responseStatus, contentType, objectMapper.writeValueAsString(headers), body,
                        scope, keyHash);
            } catch (IOException exception) {
                throw new IllegalStateException("could not serialize cached response headers", exception);
            }
        }
        return new CachedResponse(responseStatus, contentType, headers, body);
    }

    private String requestHash(HttpServletRequest request, byte[] body) {
        byte[] canonicalBody = body;
        if (body.length > 0 && request.getContentType() != null
                && request.getContentType().toLowerCase().contains(MediaType.APPLICATION_JSON_VALUE)) {
            try {
                JsonNode parsed = objectMapper.readTree(body);
                if (parsed != null) {
                    canonicalBody = objectMapper.writeValueAsBytes(canonicalize(parsed));
                }
            } catch (IOException ignored) {
                canonicalBody = body;
            }
        }
        String query = request.getQueryString() == null ? "" : request.getQueryString();
        ByteArrayOutputStream digestInput = new ByteArrayOutputStream();
        try {
            digestInput.write(request.getMethod().toUpperCase().getBytes(StandardCharsets.UTF_8));
            digestInput.write('\n');
            digestInput.write(request.getRequestURI().getBytes(StandardCharsets.UTF_8));
            digestInput.write('\n');
            digestInput.write((request.getContentType() == null ? "" : request.getContentType())
                    .getBytes(StandardCharsets.UTF_8));
            digestInput.write('\n');
            digestInput.write(query.getBytes(StandardCharsets.UTF_8));
            digestInput.write('\n');
            digestInput.write(canonicalBody);
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
        return sha256(digestInput.toByteArray());
    }

    private JsonNode canonicalize(JsonNode node) {
        if (node.isObject()) {
            ObjectNode sorted = objectMapper.createObjectNode();
            TreeMap<String, JsonNode> fields = new TreeMap<>();
            node.fields().forEachRemaining(entry -> fields.put(entry.getKey(), entry.getValue()));
            fields.forEach((name, value) -> sorted.set(name, canonicalize(value)));
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode array = objectMapper.createArrayNode();
            node.forEach(value -> array.add(canonicalize(value)));
            return array;
        }
        return node;
    }

    private String scope(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String actor = authentication == null ? "anonymous" : authentication.getName();
        return actor + ":" + request.getMethod().toUpperCase() + ":" + sha256(
                request.getRequestURI().getBytes(StandardCharsets.UTF_8));
    }

    private IdempotencyRow readRow(ResultSet rs) throws SQLException {
        String serializedHeaders = rs.getString("response_headers");
        Map<String, String> headers = Map.of();
        if (serializedHeaders != null && !serializedHeaders.isBlank()) {
            try {
                headers = objectMapper.readValue(serializedHeaders,
                        objectMapper.getTypeFactory().constructMapType(Map.class, String.class, String.class));
            } catch (IOException exception) {
                throw new SQLException("invalid cached response headers", exception);
            }
        }
        return new IdempotencyRow(rs.getString("request_hash"), rs.getString("status"),
                (Integer) rs.getObject("response_status"), rs.getString("response_content_type"),
                headers, rs.getBytes("response_body"), rs.getTimestamp("expires_at").toInstant());
    }

    private void writeError(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), new ApiErrorResponse(
                new ApiError(code, message, false, Map.of()), RequestContext.traceId()));
    }

    private CachedResponse errorResponse(int status, String code, String message) {
        try {
            return new CachedResponse(status, MediaType.APPLICATION_JSON_VALUE, Map.of(), objectMapper.writeValueAsBytes(
                    new ApiErrorResponse(new ApiError(code, message, false, Map.of()), RequestContext.traceId())));
        } catch (IOException exception) {
            throw new IllegalStateException("could not serialize idempotency error", exception);
        }
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record IdempotencyRow(String requestHash, String status, Integer responseStatus,
                                  String responseContentType, Map<String, String> headers, byte[] body,
                                  Instant expiresAt) {
    }

    private record CachedResponse(int status, String contentType, Map<String, String> headers, byte[] body) {
    }

    private static class FilterExecutionException extends RuntimeException {
        FilterExecutionException(Exception cause) {
            super(cause);
        }
    }

    private static class CachedRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        CachedRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return input.read();
                }

                @Override
                public boolean isFinished() {
                    return input.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener readListener) {
                    throw new UnsupportedOperationException("asynchronous request bodies are not supported");
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), getCharacterEncoding() == null
                    ? StandardCharsets.UTF_8 : java.nio.charset.Charset.forName(getCharacterEncoding())));
        }
    }
}
