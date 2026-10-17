package com.peakdispatch.application.knowledge;

import java.time.Instant;
import java.util.List;

public interface KnowledgeSearchGateway {
    void index(String physicalIndex, List<KnowledgeService.Chunk> chunks);
    void verify(String physicalIndex, int expectedChunkCount);
    void verifyEmpty(String physicalIndex);
    void activateAlias(String physicalIndex, String previousPhysicalIndex);
    SearchResult search(String physicalIndex, String query, String regionId, int limit, Instant asOf);

    default SearchResult search(String physicalIndex, String query, String regionId, String category,
                                int limit, Instant asOf) {
        return search(physicalIndex, query, regionId, limit, asOf);
    }

    record SearchResult(boolean available, List<KnowledgeService.Citation> citations, String errorCode) {
        public static SearchResult noEvidence(String code) {
            return new SearchResult(false, List.of(), code);
        }
    }
}
