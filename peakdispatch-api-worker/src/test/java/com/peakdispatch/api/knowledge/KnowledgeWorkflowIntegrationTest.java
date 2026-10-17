package com.peakdispatch.api.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.peakdispatch.application.knowledge.KnowledgeRepository;
import com.peakdispatch.application.knowledge.KnowledgeSearchGateway;
import com.peakdispatch.application.knowledge.KnowledgeService;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class KnowledgeWorkflowIntegrationTest {
    @Autowired KnowledgeService knowledge;
    @Autowired KnowledgeRepository repository;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mockMvc;
    @MockitoBean KnowledgeSearchGateway search;

    @BeforeEach
    void configureSearchGateway() {
        doNothing().when(search).index(anyString(), any());
        doNothing().when(search).verify(anyString(), anyInt());
        doNothing().when(search).verifyEmpty(anyString());
        doNothing().when(search).activateAlias(anyString(), any());
    }

    @Test
    void ingestAndDeactivate_rebuildsFullIndexAndFiltersInactiveCitations() {
        var first = knowledge.ingest(request("kb-full-a", "First policy"), "knowledge-test-actor", "trace-kb");
        var second = knowledge.ingest(request("kb-full-b", "Second policy"), "knowledge-test-actor", "trace-kb");
        verify(search).index(anyString(), argThat(chunks -> chunks.stream()
                .anyMatch(chunk -> chunk.documentId().equals(first.id()))
                && chunks.stream().anyMatch(chunk -> chunk.documentId().equals(second.id()))));

        knowledge.deactivate(first.id(), "knowledge-test-actor", "trace-kb");
        when(search.search(anyString(), anyString(), anyString(), anyInt(), any())).thenReturn(
                new KnowledgeSearchGateway.SearchResult(true, List.of(new KnowledgeService.Citation(
                        "stale-hit", first.id(), first.externalKey(), first.version(), 0, first.title(), "",
                        first.content(), "knowledge://" + first.externalKey() + "/v1#0", 2.4)), null));
        var result = knowledge.search("policy", "test-other-region", 5, "knowledge-test-actor", "trace-kb");

        assertThat(repository.find(first.id()).orElseThrow().status()).isEqualTo("INACTIVE");
        assertThat(repository.find(second.id()).orElseThrow().status()).isEqualTo("ACTIVE");
        assertThat(result.status()).isEqualTo("NO_EVIDENCE");
        assertThat(result.citations()).isEmpty();
        verify(search, atLeast(3)).activateAlias(anyString(), any());
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_log WHERE actor_id = 'knowledge-test-actor'
                    AND action = 'KNOWLEDGE_DOCUMENT_DEACTIVATED' AND target_id = ?
                """, Integer.class, first.id().toString())).isEqualTo(1);
    }

    @Test
    void search_onlyReturnsActiveEvidenceForRequestedRegionAndEffectiveTime() {
        Instant now = Instant.now();
        var visible = knowledge.ingest(request("kb-visible", "Visible policy", "search-region",
                now.minusSeconds(60)), "knowledge-search-actor", "trace-kb-search");
        var otherRegion = knowledge.ingest(request("kb-other-region", "Other region policy", "other-region",
                now.minusSeconds(60)), "knowledge-search-actor", "trace-kb-search");
        var future = knowledge.ingest(request("kb-future", "Future policy", "search-region",
                now.plusSeconds(3600)), "knowledge-search-actor", "trace-kb-search");
        when(search.search(anyString(), anyString(), anyString(), anyInt(), any())).thenReturn(
                new KnowledgeSearchGateway.SearchResult(true, List.of(
                        citation(visible), citation(otherRegion), citation(future)), null));

        var result = knowledge.search("policy", "search-region", 5,
                "knowledge-search-actor", "trace-kb-search");

        assertThat(result.status()).isEqualTo("EVIDENCE");
        assertThat(result.citations()).extracting(KnowledgeService.Citation::documentId)
                .containsExactly(visible.id());
    }

    @Test
    void indexingFailure_persistsFailedDocumentIndexAndAudit() {
        int failuresBefore = jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_log WHERE actor_id = 'knowledge-failure-actor'
                    AND action = 'KNOWLEDGE_INDEX_FAILED'
                """, Integer.class);
        doThrow(new IllegalStateException("simulated gateway failure"))
                .when(search).index(anyString(), any());

        assertThatThrownBy(() -> knowledge.ingest(request("kb-failed", "Failure case"),
                "knowledge-failure-actor", "trace-kb-failure"))
                .isInstanceOf(KnowledgeService.IndexingException.class);

        assertThat(repository.list("test-other-region", "FAILED"))
                .anyMatch(document -> document.externalKey().equals("kb-failed"));
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM knowledge_index_version WHERE status = 'FAILED' AND failure_code IS NOT NULL
                """, Integer.class)).isGreaterThan(0);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_log WHERE actor_id = 'knowledge-failure-actor'
                    AND action = 'KNOWLEDGE_INDEX_FAILED'
                """, Integer.class) - failuresBefore).isEqualTo(1);
    }

    @Test
    void documentListing_outsideViewerRegionIsDeniedAndAudited() throws Exception {
        mockMvc.perform(get("/api/v1/knowledge/documents?regionId=blocked-region")
                        .with(SecurityMockMvcRequestPostProcessors.httpBasic("ops-viewer-test", "ops-viewer-test-password")))
                .andExpect(status().isForbidden());

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_log
                WHERE actor_id = 'ops-viewer-test' AND decision = 'DENIED'
                    AND target_id = 'blocked-region'
                """, Integer.class)).isGreaterThan(0);
    }

    private static KnowledgeService.IngestRequest request(String externalKey, String title) {
        return request(externalKey, title, "test-other-region", Instant.parse("2026-10-12T00:00:00Z"));
    }

    private static KnowledgeService.IngestRequest request(String externalKey, String title, String regionId,
                                                          Instant effectiveAt) {
        return new KnowledgeService.IngestRequest(externalKey, title, "SOP", "Dispatch", title + " evidence",
                Set.of(regionId), effectiveAt);
    }

    private static KnowledgeService.Citation citation(KnowledgeService.Document document) {
        return new KnowledgeService.Citation("citation-" + document.id(), document.id(), document.externalKey(),
                document.version(), 0, document.title(), document.section(), document.content(),
                "knowledge://" + document.externalKey() + "/v" + document.version() + "#0", 1.0);
    }
}
