package com.peakdispatch.application.knowledge;

import com.peakdispatch.application.observability.PeakDispatchMetrics;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Service
public class KnowledgeService {
    private static final int MAX_CONTENT = 2_000_000;
    private final KnowledgeRepository repository;
    private final KnowledgeSearchGateway search;
    private final Clock clock;
    private final PeakDispatchMetrics metrics;

    public KnowledgeService(KnowledgeRepository repository, KnowledgeSearchGateway search, Clock clock) {
        this(repository, search, clock, null);
    }

    @Autowired
    public KnowledgeService(KnowledgeRepository repository, KnowledgeSearchGateway search,
                            Clock clock, PeakDispatchMetrics metrics) {
        this.repository = repository;
        this.search = search;
        this.clock = clock;
        this.metrics = metrics;
    }

    @Transactional(noRollbackFor = IndexingException.class)
    public Document ingest(IngestRequest request, String actorId, String traceId) {
        validate(request);
        Instant now = Instant.now(clock);
        long version = repository.nextDocumentVersion(request.externalKey());
        UUID id = UUID.randomUUID();
        String hash = sha256(request.title() + "\n" + request.section() + "\n" + request.content());
        Document document = new Document(id, request.externalKey(), request.title(), version, request.category(),
                request.section(), request.content(), Set.copyOf(request.regionScope()), "INDEXING",
                request.effectiveAt(), hash, null, actorId, now, now);
        repository.saveDocument(document);
        repository.audit(actorId, "KNOWLEDGE_DOCUMENT_CREATED", "KNOWLEDGE_DOCUMENT", id.toString(), "APPLIED",
                "externalKey=" + request.externalKey() + ";version=" + version, traceId, now);
        long indexVersion = repository.nextIndexVersion();
        String physicalIndex = "knowledge-" + indexVersion;
        List<Chunk> chunks = repository.activeDocuments(null, id).stream()
                .flatMap(activeDocument -> chunks(activeDocument, indexVersion).stream()).toList();
        repository.createIndexVersion(indexVersion, physicalIndex, chunks.size(), actorId, now);
        String previous = repository.activeIndexVersion().map(IndexVersion::physicalIndex).orElse(null);
        try {
            search.index(physicalIndex, chunks);
            search.verify(physicalIndex, chunks.size());
            search.activateAlias(physicalIndex, previous);
        } catch (RuntimeException exception) {
            String failureCode = exception.getMessage() == null ? exception.getClass().getSimpleName()
                    : exception.getMessage().replaceAll("[^A-Za-z0-9_-]", "_").substring(0,
                    Math.min(64, exception.getMessage().replaceAll("[^A-Za-z0-9_-]", "_").length()));
            repository.failIndexVersion(indexVersion, id, failureCode, actorId, traceId, now);
            throw new IndexingException("knowledge index build failed", exception);
        }
        repository.activateIndexVersion(indexVersion, physicalIndex, previous, id, null,
                document.externalKey(), document.version(), actorId, traceId, now);
        return new Document(id, document.externalKey(), document.title(), document.version(), document.category(),
                document.section(), document.content(), document.regionScope(), "ACTIVE", document.effectiveAt(),
                document.sourceHash(), now, document.submittedBy(), document.createdAt(), now);
    }

    @Transactional(noRollbackFor = IndexingException.class)
    public void deactivate(UUID documentId, String actorId, String traceId) {
        Instant now = Instant.now(clock);
        Document document = repository.find(documentId).orElseThrow(() -> new IllegalArgumentException("knowledge document not found"));
        if (!"ACTIVE".equals(document.status())) throw new IllegalArgumentException("only an active knowledge document can be deactivated");
        long indexVersion = repository.nextIndexVersion();
        String physicalIndex = "knowledge-" + indexVersion;
        List<Chunk> chunks = repository.activeDocuments(documentId, null).stream()
                .flatMap(activeDocument -> chunks(activeDocument, indexVersion).stream()).toList();
        repository.createIndexVersion(indexVersion, physicalIndex, chunks.size(), actorId, now);
        String previous = repository.activeIndexVersion().map(IndexVersion::physicalIndex).orElse(null);
        try {
            if (!chunks.isEmpty()) {
                search.index(physicalIndex, chunks);
                search.verify(physicalIndex, chunks.size());
            } else {
                search.index(physicalIndex, List.of());
                search.verifyEmpty(physicalIndex);
            }
            search.activateAlias(physicalIndex, previous);
        } catch (RuntimeException exception) {
            String failureCode = "DEACTIVATION_INDEX_FAILED";
            repository.failIndexVersion(indexVersion, null, failureCode, actorId, traceId, now);
            repository.audit(actorId, "KNOWLEDGE_DOCUMENT_DEACTIVATION_FAILED", "KNOWLEDGE_DOCUMENT",
                    documentId.toString(), "FAILED", "indexVersion=" + indexVersion, traceId, now);
            throw new IndexingException("knowledge index rebuild failed", exception);
        }
        repository.activateIndexVersion(indexVersion, physicalIndex, previous, null, documentId,
                null, 0, actorId, traceId, now);
    }

    @Transactional
    public List<Document> list(String regionId, String status, String actorId, String traceId) {
        Instant now = Instant.now(clock);
        List<Document> documents = repository.list(regionId, status == null ? "ACTIVE" : status);
        repository.audit(actorId, "KNOWLEDGE_DOCUMENTS_READ", "KNOWLEDGE_REGION", regionId, "ALLOWED",
                "status=" + (status == null ? "ACTIVE" : status) + ";count=" + documents.size(), traceId, now);
        return documents;
    }

    @Transactional
    public IndexVersion activeIndex(String regionId, String actorId, String traceId) {
        Instant now = Instant.now(clock);
        IndexVersion active = repository.activeIndexVersion().orElse(null);
        repository.audit(actorId, "KNOWLEDGE_INDEX_READ", "KNOWLEDGE_REGION", regionId,
                active == null ? "NO_ACTIVE_INDEX" : "ALLOWED", "indexVersion="
                        + (active == null ? "none" : active.indexVersion()), traceId, now);
        return active;
    }

    @Transactional
    public SearchResponse search(String query, String regionId, int limit, String actorId, String traceId) {
        return search(query, regionId, null, limit, actorId, traceId);
    }

    @Transactional
    public SearchResponse search(String query, String regionId, String category, int limit,
                                 String actorId, String traceId) {
        if (query == null || query.isBlank() || regionId == null || limit < 1 || limit > 20
                || (category != null && !category.matches("[A-Za-z0-9_-]{1,64}"))) {
            throw new IllegalArgumentException("query, regionId, category and limit are invalid");
        }
        Instant now = Instant.now(clock);
        var active = repository.activeIndexVersion();
        if (active.isEmpty()) {
            repository.audit(actorId, "KNOWLEDGE_SEARCH", "KNOWLEDGE_INDEX", regionId, "NO_EVIDENCE",
                    "reason=NO_ACTIVE_INDEX", traceId, now);
            if (metrics != null) metrics.knowledgeSearch(0, 0, true);
            return new SearchResponse("NO_EVIDENCE", List.of(), null);
        }
        KnowledgeSearchGateway.SearchResult result;
        try {
            result = category == null
                    ? search.search(active.get().physicalIndex(), query.trim(), regionId, limit, now)
                    : search.search(active.get().physicalIndex(), query.trim(), regionId, category, limit, now);
        } catch (RuntimeException exception) {
            result = KnowledgeSearchGateway.SearchResult.noEvidence("ES_UNAVAILABLE");
        }
        List<Citation> activeCitations = result.available() ? result.citations().stream()
                .filter(citation -> repository.isSearchable(citation.documentId(), citation.version(), regionId, now))
                .toList() : List.of();
        String status = activeCitations.isEmpty() ? "NO_EVIDENCE" : "EVIDENCE";
        repository.audit(actorId, "KNOWLEDGE_SEARCH", "KNOWLEDGE_INDEX", regionId, status,
                "indexVersion=" + active.get().indexVersion() + ";hitCount=" + activeCitations.size()
                        + (result.errorCode() == null ? "" : ";error=" + result.errorCode()), traceId, now);
        if (metrics != null) metrics.knowledgeSearch(result.citations().size(), activeCitations.size(),
                activeCitations.isEmpty());
        return new SearchResponse(status, activeCitations, result.errorCode());
    }

    @Transactional
    public void auditDenied(String actorId, String action, String targetId, String details, String traceId) {
        repository.audit(actorId, action, "KNOWLEDGE", targetId, "DENIED", details,
                traceId, Instant.now(clock));
    }

    private static List<Chunk> chunks(Document document, long indexVersion) {
        String content = document.content();
        int size = 1000;
        java.util.ArrayList<Chunk> result = new java.util.ArrayList<>();
        int chunk = 0;
        for (int start = 0; start < content.length(); start += size) {
            int end = Math.min(content.length(), start + size);
            result.add(new Chunk(document.id(), document.externalKey(), document.version(), indexVersion,
                    chunk++, document.title(), document.section(), content.substring(start, end),
                    document.regionScope(), document.category(), document.effectiveAt(), document.sourceHash()));
        }
        return List.copyOf(result);
    }

    private static void validate(IngestRequest request) {
        if (request == null || request.externalKey() == null || !request.externalKey().matches("[A-Za-z0-9._:-]{1,255}")
                || request.title() == null || request.title().isBlank() || request.title().length() > 512
                || request.content() == null || request.content().isBlank() || request.content().length() > MAX_CONTENT
                || request.category() == null || !request.category().matches("[A-Za-z0-9_-]{1,64}")
                || request.regionScope() == null || request.regionScope().isEmpty()
                || request.regionScope().stream().anyMatch(region -> region == null || !region.matches("[A-Za-z0-9_-]{1,64}"))) {
            throw new IllegalArgumentException("knowledge document fields are invalid");
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public record IngestRequest(String externalKey, String title, String category, String section, String content,
                                Set<String> regionScope, Instant effectiveAt) {
    }

    public record Document(UUID id, String externalKey, String title, long version, String category, String section,
                           String content, Set<String> regionScope, String status, Instant effectiveAt, String sourceHash,
                           Instant indexedAt, String submittedBy, Instant createdAt, Instant updatedAt) {
    }

    public record IndexVersion(long indexVersion, String physicalIndex, String status, int chunkCount,
                               Instant createdAt, Instant activatedAt) {
    }

    public record Chunk(UUID documentId, String externalKey, long version, long indexVersion, int chunkId,
                        String title, String section, String content, Set<String> regionScope, String category,
                        Instant effectiveAt, String sourceHash) {
    }

    public record Citation(String citationId, UUID documentId, String externalKey, long version, int chunkId,
                           String title, String section, String excerpt, String uri, double score) {
    }

    public record SearchResponse(String status, List<Citation> citations, String errorCode) {
    }

    public static class IndexingException extends RuntimeException {
        public IndexingException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
