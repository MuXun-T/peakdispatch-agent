package com.peakdispatch.api.knowledge;

import com.peakdispatch.api.system.ApiResponse;
import com.peakdispatch.api.system.RequestContext;
import com.peakdispatch.application.knowledge.KnowledgeRepository;
import com.peakdispatch.application.knowledge.KnowledgeService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/knowledge")
@ConditionalOnExpression("'${peakdispatch.runtime.role:ALL}' != 'WORKER'")
public class KnowledgeController {
    private final KnowledgeService knowledge;
    private final KnowledgeRepository repository;

    public KnowledgeController(KnowledgeService knowledge, KnowledgeRepository repository) {
        this.knowledge = knowledge;
        this.repository = repository;
    }

    @PostMapping("/documents")
    @PreAuthorize("hasAnyRole('OPS_APPROVER', 'OPS_ADMIN')")
    public ApiResponse<KnowledgeService.Document> ingest(@Valid @RequestBody DocumentRequest request,
                                                         Authentication authentication) {
        requireAnyRegion(authentication, request.regionScope(), "KNOWLEDGE_DOCUMENT_CREATE",
                request.externalKey(), RequestContext.traceId());
        return new ApiResponse<>(knowledge.ingest(new KnowledgeService.IngestRequest(request.externalKey(),
                request.title(), request.category(), request.section(), request.content(), request.regionScope(),
                request.effectiveAt()), authentication.getName(), RequestContext.traceId()));
    }

    @GetMapping("/documents")
    @PreAuthorize("hasAnyRole('OPS_VIEWER', 'OPS_APPROVER', 'OPS_ADMIN')")
    public ApiResponse<List<KnowledgeService.Document>> documents(@RequestParam String regionId,
                                                                   @RequestParam(required = false) String status,
                                                                   Authentication authentication) {
        requireRegion(authentication, regionId, "KNOWLEDGE_DOCUMENTS_READ", regionId);
        return new ApiResponse<>(knowledge.list(regionId, status, authentication.getName(), RequestContext.traceId()));
    }

    @PostMapping("/documents/{documentId}/deactivate")
    @PreAuthorize("hasAnyRole('OPS_APPROVER', 'OPS_ADMIN')")
    public ApiResponse<Void> deactivate(@PathVariable UUID documentId, Authentication authentication) {
        KnowledgeService.Document document = repository.find(documentId)
                .orElseThrow(() -> new IllegalArgumentException("knowledge document not found"));
        requireAnyRegion(authentication, document.regionScope(), "KNOWLEDGE_DOCUMENT_DEACTIVATE",
                documentId.toString(), RequestContext.traceId());
        knowledge.deactivate(documentId, authentication.getName(), RequestContext.traceId());
        return new ApiResponse<>(null);
    }

    @GetMapping("/search")
    @PreAuthorize("hasAnyRole('OPS_VIEWER', 'OPS_APPROVER', 'OPS_ADMIN')")
    public ApiResponse<KnowledgeService.SearchResponse> search(@RequestParam String q,
                                                               @RequestParam String regionId,
                                                               @RequestParam(defaultValue = "5") @Min(1) @Max(20) int limit,
                                                               Authentication authentication) {
        requireRegion(authentication, regionId, "KNOWLEDGE_SEARCH", regionId);
        return new ApiResponse<>(knowledge.search(q, regionId, limit, authentication.getName(),
                RequestContext.traceId()));
    }

    @GetMapping("/index")
    @PreAuthorize("hasAnyRole('OPS_VIEWER', 'OPS_APPROVER', 'OPS_ADMIN')")
    public ApiResponse<KnowledgeService.IndexVersion> activeIndex(@RequestParam String regionId,
                                                                   Authentication authentication) {
        requireRegion(authentication, regionId, "KNOWLEDGE_INDEX_READ", regionId);
        return new ApiResponse<>(knowledge.activeIndex(regionId, authentication.getName(), RequestContext.traceId()));
    }

    private void requireRegion(Authentication authentication, String regionId, String action, String targetId) {
        requireAnyRegion(authentication, Set.of(regionId), action, targetId, RequestContext.traceId());
    }

    private void requireAnyRegion(Authentication authentication, Set<String> regions, String action,
                                  String targetId, String traceId) {
        boolean allowed = regions.stream().allMatch(region -> authentication.getAuthorities().stream()
                .anyMatch(authority -> ("REGION_" + region).equals(authority.getAuthority())));
        if (!allowed) {
            knowledge.auditDenied(authentication.getName(), action, targetId,
                    "regions=" + String.join(",", regions), traceId);
            throw new AccessDeniedException("knowledge is outside the authenticated user's region scope");
        }
    }

    public record DocumentRequest(@NotBlank String externalKey, @NotBlank String title,
                                  @NotBlank String category, String section, @NotBlank String content,
                                  @NotEmpty Set<String> regionScope, Instant effectiveAt) {
    }
}
