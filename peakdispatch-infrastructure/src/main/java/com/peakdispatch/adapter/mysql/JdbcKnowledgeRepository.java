package com.peakdispatch.adapter.mysql;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.peakdispatch.application.knowledge.KnowledgeRepository;
import com.peakdispatch.application.knowledge.KnowledgeService;
import com.peakdispatch.application.security.SensitiveDataRedactor;
import java.nio.ByteBuffer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcKnowledgeRepository implements KnowledgeRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcKnowledgeRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public long nextDocumentVersion(String externalKey) {
        jdbc.update("INSERT IGNORE INTO knowledge_document_counter (external_key, next_version) VALUES (?, 1)",
                externalKey);
        Long next = jdbc.queryForObject("SELECT next_version FROM knowledge_document_counter WHERE external_key = ? FOR UPDATE",
                Long.class, externalKey);
        if (next == null) throw new IllegalStateException("knowledge document counter is missing");
        jdbc.update("UPDATE knowledge_document_counter SET next_version = next_version + 1 WHERE external_key = ?",
                externalKey);
        return next;
    }

    @Override
    public void saveDocument(KnowledgeService.Document document) {
        jdbc.update("""
                INSERT INTO knowledge_document
                    (id, external_key, title, version, source_hash, region_scope, status, effective_at,
                     indexed_at, created_at, category, section, content, submitted_by, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, bytes(document.id()), document.externalKey(), document.title(), document.version(),
                document.sourceHash(), json(document.regionScope()), document.status(), ts(document.effectiveAt()),
                ts(document.indexedAt()), ts(document.createdAt()), document.category(), document.section(),
                document.content(), document.submittedBy(), ts(document.updatedAt()));
    }

    @Override
    public void markIndexed(UUID documentId, String status, Instant indexedAt) {
        jdbc.update("UPDATE knowledge_document SET status = ?, indexed_at = ?, updated_at = ? WHERE id = ?",
                status, ts(indexedAt), ts(indexedAt), bytes(documentId));
    }

    @Override
    public void deactivate(UUID documentId, String actorId, Instant now) {
        jdbc.update("UPDATE knowledge_document SET status = 'INACTIVE', updated_at = ? WHERE id = ?",
                ts(now), bytes(documentId));
    }

    @Override
    public Optional<KnowledgeService.Document> find(UUID documentId) {
        return jdbc.query("""
                SELECT id, external_key, title, version, category, section, content, region_scope, status,
                       effective_at, source_hash, indexed_at, submitted_by, created_at, updated_at
                FROM knowledge_document WHERE id = ?
                """, this::map, bytes(documentId)).stream().findFirst();
    }

    @Override
    public List<KnowledgeService.Document> list(String regionId, String status) {
        return jdbc.query("""
                SELECT id, external_key, title, version, category, section, content, region_scope, status,
                       effective_at, source_hash, indexed_at, submitted_by, created_at, updated_at
                FROM knowledge_document
                WHERE status = ? AND (region_scope LIKE ? OR region_scope LIKE ?)
                ORDER BY external_key, version DESC
                """, this::map, status, "%\"" + regionId + "\"%", "%" + regionId + "%");
    }

    @Override
    public List<KnowledgeService.Document> activeDocuments(UUID excludedDocumentId, UUID includedDocumentId) {
        List<KnowledgeService.Document> documents = new java.util.ArrayList<>(jdbc.query("""
                SELECT id, external_key, title, version, category, section, content, region_scope, status,
                       effective_at, source_hash, indexed_at, submitted_by, created_at, updated_at
                FROM knowledge_document WHERE status = 'ACTIVE'
                ORDER BY external_key, version DESC
                """, this::map));
        KnowledgeService.Document included = includedDocumentId == null ? null : find(includedDocumentId).orElse(null);
        if (included != null) {
            documents.removeIf(document -> document.externalKey().equals(included.externalKey()));
            documents.add(included);
        }
        if (excludedDocumentId != null) documents.removeIf(document -> document.id().equals(excludedDocumentId));
        return List.copyOf(documents);
    }

    @Override
    public long nextIndexVersion() {
        Long next = jdbc.queryForObject("SELECT next_version FROM knowledge_index_counter WHERE counter_id = 1 FOR UPDATE",
                Long.class);
        if (next == null) throw new IllegalStateException("knowledge index counter is missing");
        jdbc.update("UPDATE knowledge_index_counter SET next_version = next_version + 1 WHERE counter_id = 1");
        return next;
    }

    @Override
    public void createIndexVersion(long version, String physicalIndex, int chunkCount, String actorId, Instant now) {
        jdbc.update("""
                INSERT INTO knowledge_index_version
                    (index_version, physical_index, status, chunk_count, created_by, created_at)
                VALUES (?, ?, 'BUILDING', ?, ?, ?)
                """, version, physicalIndex, chunkCount, actorId, ts(now));
    }

    @Override
    @Transactional
    public void activateIndexVersion(long version, String physicalIndex, String previousPhysicalIndex,
                                     UUID publishedDocumentId, UUID deactivatedDocumentId,
                                     String supersededExternalKey, long publishedDocumentVersion,
                                     String actorId, String traceId, Instant now) {
        if (previousPhysicalIndex != null) {
            jdbc.update("UPDATE knowledge_index_version SET status = 'RETIRED' WHERE physical_index = ?",
                    previousPhysicalIndex);
        }
        jdbc.update("UPDATE knowledge_index_version SET status = 'ACTIVE', activated_at = ? WHERE index_version = ?",
                ts(now), version);
        if (supersededExternalKey != null) {
            jdbc.update("""
                    UPDATE knowledge_document SET status = 'INACTIVE', updated_at = ?
                    WHERE external_key = ? AND version < ? AND status = 'ACTIVE'
                    """, ts(now), supersededExternalKey, publishedDocumentVersion);
        }
        if (deactivatedDocumentId != null) {
            jdbc.update("UPDATE knowledge_document SET status = 'INACTIVE', updated_at = ? WHERE id = ?",
                    ts(now), bytes(deactivatedDocumentId));
        }
        if (publishedDocumentId != null) {
            jdbc.update("UPDATE knowledge_document SET status = 'ACTIVE', indexed_at = ?, updated_at = ? WHERE id = ?",
                    ts(now), ts(now), bytes(publishedDocumentId));
        }
        audit(actorId, "KNOWLEDGE_INDEX_ACTIVATED", "KNOWLEDGE_INDEX", Long.toString(version), "APPLIED",
                "physicalIndex=" + physicalIndex, traceId, now);
        if (publishedDocumentId != null) {
            audit(actorId, "KNOWLEDGE_DOCUMENT_PUBLISHED", "KNOWLEDGE_DOCUMENT", publishedDocumentId.toString(),
                    "APPLIED", "indexVersion=" + version, traceId, now);
        }
        if (deactivatedDocumentId != null) {
            audit(actorId, "KNOWLEDGE_DOCUMENT_DEACTIVATED", "KNOWLEDGE_DOCUMENT",
                    deactivatedDocumentId.toString(), "APPLIED", "indexVersion=" + version, traceId, now);
        }
    }

    @Override
    @Transactional
    public void failIndexVersion(long version, UUID documentId, String failureCode, String actorId,
                                 String traceId, Instant now) {
        jdbc.update("UPDATE knowledge_index_version SET status = 'FAILED', failure_code = ? WHERE index_version = ?",
                failureCode, version);
        if (documentId != null) {
            jdbc.update("UPDATE knowledge_document SET status = 'FAILED', updated_at = ? WHERE id = ?",
                    ts(now), bytes(documentId));
        }
        audit(actorId, "KNOWLEDGE_INDEX_FAILED", "KNOWLEDGE_DOCUMENT",
                documentId == null ? Long.toString(version) : documentId.toString(), "FAILED",
                "indexVersion=" + version + ";error=" + failureCode, traceId, now);
    }

    @Override
    public boolean isSearchable(UUID documentId, long version, String regionId, Instant asOf) {
        return find(documentId).filter(document -> document.version() == version
                && "ACTIVE".equals(document.status())
                && document.regionScope().contains(regionId)
                && (document.effectiveAt() == null || !document.effectiveAt().isAfter(asOf)))
                .isPresent();
    }

    @Override
    public Optional<KnowledgeService.IndexVersion> activeIndexVersion() {
        return jdbc.query("""
                SELECT index_version, physical_index, status, chunk_count, created_at, activated_at
                FROM knowledge_index_version WHERE status = 'ACTIVE'
                ORDER BY index_version DESC
                """, (rs, row) -> new KnowledgeService.IndexVersion(rs.getLong("index_version"),
                rs.getString("physical_index"), rs.getString("status"), rs.getInt("chunk_count"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("activated_at") == null ? null : rs.getTimestamp("activated_at").toInstant())).stream().findFirst();
    }

    @Override
    public void audit(String actorId, String action, String targetType, String targetId, String decision,
                      String details, String traceId, Instant occurredAt) {
        jdbc.update("""
                INSERT INTO audit_log (id, actor_id, action, target_type, target_id, decision, trace_id,
                    details_redacted, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, bytes(UUID.randomUUID()), actorId, action, targetType, targetId, decision, traceId,
                SensitiveDataRedactor.redact(details), ts(occurredAt));
    }

    private KnowledgeService.Document map(ResultSet rs, int row) throws SQLException {
        try {
            return new KnowledgeService.Document(uuid(rs.getBytes("id")), rs.getString("external_key"),
                    rs.getString("title"), rs.getLong("version"), rs.getString("category"),
                    rs.getString("section"), rs.getString("content"), mapper.readValue(rs.getString("region_scope"),
                    mapper.getTypeFactory().constructCollectionType(Set.class, String.class)),
                    rs.getString("status"), instant(rs, "effective_at"), rs.getString("source_hash"),
                    instant(rs, "indexed_at"), rs.getString("submitted_by"), instant(rs, "created_at"),
                    instant(rs, "updated_at"));
        } catch (JsonProcessingException exception) {
            throw new SQLException("invalid knowledge region scope", exception);
        }
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("knowledge field cannot be serialized", exception);
        }
    }

    private static Timestamp ts(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static byte[] bytes(UUID value) {
        return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits()).array();
    }

    private static UUID uuid(byte[] value) {
        ByteBuffer buffer = ByteBuffer.wrap(value);
        return new UUID(buffer.getLong(), buffer.getLong());
    }
}
