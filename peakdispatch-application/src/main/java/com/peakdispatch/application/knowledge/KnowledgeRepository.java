package com.peakdispatch.application.knowledge;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.Optional;

public interface KnowledgeRepository {
    long nextDocumentVersion(String externalKey);
    void saveDocument(KnowledgeService.Document document);
    void markIndexed(UUID documentId, String status, Instant indexedAt);
    void deactivate(UUID documentId, String actorId, Instant now);
    Optional<KnowledgeService.Document> find(UUID documentId);
    List<KnowledgeService.Document> list(String regionId, String status);
    List<KnowledgeService.Document> activeDocuments(UUID excludedDocumentId, UUID includedDocumentId);
    long nextIndexVersion();
    void createIndexVersion(long version, String physicalIndex, int chunkCount, String actorId, Instant now);
    void activateIndexVersion(long version, String physicalIndex, String previousPhysicalIndex,
                              UUID publishedDocumentId, UUID deactivatedDocumentId,
                              String supersededExternalKey, long publishedDocumentVersion,
                              String actorId, String traceId, Instant now);
    void failIndexVersion(long version, UUID documentId, String failureCode, String actorId, String traceId, Instant now);
    boolean isSearchable(UUID documentId, long version, String regionId, Instant asOf);
    Optional<KnowledgeService.IndexVersion> activeIndexVersion();
    void audit(String actorId, String action, String targetType, String targetId, String decision,
               String details, String traceId, Instant occurredAt);
}
