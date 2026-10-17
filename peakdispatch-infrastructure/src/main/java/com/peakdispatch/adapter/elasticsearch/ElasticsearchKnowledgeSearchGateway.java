package com.peakdispatch.adapter.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.mapping.Property;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import com.peakdispatch.application.knowledge.KnowledgeSearchGateway;
import com.peakdispatch.application.knowledge.KnowledgeService;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class ElasticsearchKnowledgeSearchGateway implements KnowledgeSearchGateway {
    public static final String ACTIVE_ALIAS = "knowledge-active";
    private final ElasticsearchClient client;

    public ElasticsearchKnowledgeSearchGateway(ElasticsearchClient client) {
        this.client = client;
    }

    @Override
    public void index(String physicalIndex, List<KnowledgeService.Chunk> chunks) {
        try {
            client.indices().create(c -> c.index(physicalIndex)
                    .mappings(m -> m.properties("title", Property.of(p -> p.text(t -> t)))
                            .properties("section", Property.of(p -> p.text(t -> t)))
                            .properties("content", Property.of(p -> p.text(t -> t)))
                            .properties("regionScope", Property.of(p -> p.keyword(k -> k)))
                            .properties("category", Property.of(p -> p.keyword(k -> k)))
                            .properties("effectiveAt", Property.of(p -> p.date(d -> d)))
                            .properties("status", Property.of(p -> p.keyword(k -> k)))));
            for (KnowledgeService.Chunk chunk : chunks) {
                Map<String, Object> document = new HashMap<>();
                document.put("documentId", chunk.documentId().toString());
                document.put("externalKey", chunk.externalKey());
                document.put("version", chunk.version());
                document.put("indexVersion", chunk.indexVersion());
                document.put("chunkId", chunk.chunkId());
                document.put("title", chunk.title());
                document.put("section", chunk.section());
                document.put("content", chunk.content());
                document.put("regionScope", chunk.regionScope());
                document.put("category", chunk.category());
                document.put("effectiveAt", chunk.effectiveAt());
                document.put("status", "ACTIVE");
                document.put("sourceHash", chunk.sourceHash());
                client.index(i -> i.index(physicalIndex).id(chunk.documentId() + "-" + chunk.chunkId()).document(document));
            }
        } catch (IOException exception) {
            throw new IllegalStateException("ELASTICSEARCH_UNAVAILABLE", exception);
        }
    }

    @Override
    public void verify(String physicalIndex, int expectedChunkCount) {
        try {
            client.indices().refresh(r -> r.index(physicalIndex));
            long count = client.count(c -> c.index(physicalIndex)).count();
            if (count != expectedChunkCount || count == 0) {
                throw new IllegalStateException("ELASTICSEARCH_CHUNK_COUNT_MISMATCH");
            }
            client.search(s -> s.index(physicalIndex).size(1).query(q -> q.matchAll(m -> m)), Map.class);
        } catch (IOException exception) {
            throw new IllegalStateException("ELASTICSEARCH_UNAVAILABLE", exception);
        }
    }

    @Override
    public void verifyEmpty(String physicalIndex) {
        try {
            client.indices().refresh(r -> r.index(physicalIndex));
            long count = client.count(c -> c.index(physicalIndex)).count();
            if (count != 0) throw new IllegalStateException("ELASTICSEARCH_INDEX_NOT_EMPTY");
        } catch (IOException exception) {
            throw new IllegalStateException("ELASTICSEARCH_UNAVAILABLE", exception);
        }
    }

    @Override
    public void activateAlias(String physicalIndex, String previousPhysicalIndex) {
        try {
            var actions = new ArrayList<co.elastic.clients.elasticsearch.indices.update_aliases.Action>();
            if (previousPhysicalIndex != null) {
                actions.add(co.elastic.clients.elasticsearch.indices.update_aliases.Action.of(a ->
                        a.remove(r -> r.index(previousPhysicalIndex).alias(ACTIVE_ALIAS))));
            }
            actions.add(co.elastic.clients.elasticsearch.indices.update_aliases.Action.of(a ->
                    a.add(add -> add.index(physicalIndex).alias(ACTIVE_ALIAS))));
            client.indices().updateAliases(u -> u.actions(actions));
        } catch (IOException exception) {
            throw new IllegalStateException("ELASTICSEARCH_UNAVAILABLE", exception);
        }
    }

    @Override
    public SearchResult search(String physicalIndex, String query, String regionId, int limit, Instant asOf) {
        return search(physicalIndex, query, regionId, null, limit, asOf);
    }

    @Override
    public SearchResult search(String physicalIndex, String query, String regionId, String category,
                               int limit, Instant asOf) {
        try {
            Query regionFilter = Query.of(q -> q.term(t -> t.field("regionScope").value(FieldValue.of(regionId))));
            Query activeFilter = Query.of(q -> q.term(t -> t.field("status").value(FieldValue.of("ACTIVE"))));
            Query effectiveFilter = Query.of(q -> q.bool(b -> b
                    .should(s -> s.range(r -> r.date(d -> d.field("effectiveAt").lte(asOf.toString()))))
                    .should(s -> s.bool(inner -> inner.mustNot(m -> m.exists(e -> e.field("effectiveAt")))))
                    .minimumShouldMatch("1")));
            Query categoryFilter = category == null ? null : Query.of(q -> q.term(
                    term -> term.field("category").value(FieldValue.of(category))));
            var response = client.search(s -> s.index(physicalIndex).size(limit)
                    .query(q -> q.bool(b -> {
                        b.must(m -> m.multiMatch(mm -> mm.query(query)
                                .fields("title^2", "section", "content")))
                                .filter(regionFilter).filter(activeFilter).filter(effectiveFilter);
                        if (categoryFilter != null) b.filter(categoryFilter);
                        return b;
                    })), Map.class);
            List<KnowledgeService.Citation> citations = new ArrayList<>();
            response.hits().hits().forEach(hit -> {
                Map source = hit.source();
                if (source == null) return;
                String documentId = String.valueOf(source.get("documentId"));
                String externalKey = String.valueOf(source.get("externalKey"));
                String title = String.valueOf(source.get("title"));
                String section = String.valueOf(source.get("section"));
                String content = String.valueOf(source.get("content"));
                long version = number(source.get("version"));
                int chunkId = (int) number(source.get("chunkId"));
                citations.add(new KnowledgeService.Citation(hit.id(), java.util.UUID.fromString(documentId),
                        externalKey, version, chunkId, title, section, content,
                        "knowledge://" + externalKey + "/v" + version + "#" + chunkId,
                        hit.score() == null ? 0 : hit.score()));
            });
            return new SearchResult(true, List.copyOf(citations), null);
        } catch (IOException | RuntimeException exception) {
            return SearchResult.noEvidence("ES_UNAVAILABLE");
        }
    }

    private static long number(Object value) {
        return value instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(value));
    }
}
