package com.peakdispatch.application.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.peakdispatch.application.order.OrderApplicationService;
import com.peakdispatch.application.order.OrderVersionConflictException;
import com.peakdispatch.application.security.SensitiveDataRedactor;
import com.peakdispatch.application.observability.PeakDispatchMetrics;
import com.peakdispatch.domain.agent.AgentActionStatus;
import com.peakdispatch.domain.agent.AgentRunStatus;
import com.peakdispatch.domain.order.DeliveryOrder;
import com.peakdispatch.domain.order.OrderStatus;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentConversationService {
    private static final Duration RECOVERY_AGE = Duration.ofSeconds(10);
    private static final double MIN_INTENT_CONFIDENCE = 0.55;
    private final AgentRepository repository;
    private final AgentToolGateway toolGateway;
    private final AgentIntentClassifier intentClassifier;
    private final DeterministicMockAgent mockAgent;
    private final OrderApplicationService orders;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final AgentExecutionConcurrency concurrency;
    private final PeakDispatchMetrics metrics;
    private final TaskExecutor runExecutor;
    private final Set<UUID> scheduledRuns = ConcurrentHashMap.newKeySet();

    public AgentConversationService(AgentRepository repository, AgentToolGateway toolGateway,
                                   AgentIntentClassifier intentClassifier, DeterministicMockAgent mockAgent,
                                   OrderApplicationService orders,
                                   ObjectMapper mapper, Clock clock) {
        this(repository, toolGateway, intentClassifier, mockAgent, orders, mapper, clock,
                AgentExecutionConcurrency.noop(), null, Runnable::run);
    }

    public AgentConversationService(AgentRepository repository, AgentToolGateway toolGateway,
                                    AgentIntentClassifier intentClassifier, DeterministicMockAgent mockAgent,
                                    OrderApplicationService orders, ObjectMapper mapper, Clock clock,
                                    AgentExecutionConcurrency concurrency, PeakDispatchMetrics metrics) {
        this(repository, toolGateway, intentClassifier, mockAgent, orders, mapper, clock,
                concurrency, metrics, Runnable::run);
    }

    @Autowired
    public AgentConversationService(AgentRepository repository, AgentToolGateway toolGateway,
                                    AgentIntentClassifier intentClassifier, DeterministicMockAgent mockAgent,
                                    OrderApplicationService orders, ObjectMapper mapper, Clock clock,
                                    AgentExecutionConcurrency concurrency, PeakDispatchMetrics metrics,
                                    @Qualifier("agentRunTaskExecutor") TaskExecutor runExecutor) {
        this.repository = repository;
        this.toolGateway = toolGateway;
        this.intentClassifier = intentClassifier;
        this.mockAgent = mockAgent;
        this.orders = orders;
        this.mapper = mapper;
        this.clock = clock;
        this.concurrency = concurrency;
        this.metrics = metrics;
        this.runExecutor = runExecutor;
    }

    public RunSummary chat(ChatCommand command, String requestKey, AgentPrincipal principal, String traceId) {
        try (AgentExecutionConcurrency.Lease ignored = concurrency.acquireRun(principal.actorId())) {
            PreparedRun prepared = createRun(command, requestKey, principal, traceId, null);
            if (prepared.run().status() == AgentRunStatus.RUNNING) {
                executeWithExecutionLease(prepared.run().runId(), prepared.principal(), traceId);
            }
            return summary(run(prepared.run().runId()));
        }
    }

    public RunSummary chatWithServerPlan(ChatCommand command, String requestKey, AgentPrincipal principal,
                                         String traceId, AgentPlan serverPlan) {
        if (serverPlan == null) throw new IllegalArgumentException("server plan is required");
        try (AgentExecutionConcurrency.Lease ignored = concurrency.acquireRun(principal.actorId())) {
            PreparedRun prepared = createRun(command, requestKey, principal, traceId, serverPlan);
            if (prepared.run().status() == AgentRunStatus.RUNNING) {
                executeWithExecutionLease(prepared.run().runId(), prepared.principal(), traceId, serverPlan);
            }
            return summary(run(prepared.run().runId()));
        }
    }

    public RunSummary startChat(ChatCommand command, String requestKey, AgentPrincipal principal, String traceId) {
        PreparedRun prepared = createRun(command, requestKey, principal, traceId, null);
        UUID runId = prepared.run().runId();
        if (prepared.run().status() == AgentRunStatus.RUNNING && scheduledRuns.add(runId)) {
            try {
                runExecutor.execute(() -> {
                    try {
                        executeWithRunLease(runId, prepared.principal(), traceId);
                    } finally {
                        scheduledRuns.remove(runId);
                    }
                });
            } catch (RuntimeException rejected) {
                scheduledRuns.remove(runId);
            }
        }
        return summary(run(runId));
    }

    private PreparedRun createRun(ChatCommand command, String requestKey,
                                  AgentPrincipal principal, String traceId, AgentPlan serverPlan) {
        requireReader(principal);
        validateCommand(command, requestKey);
        String redactedMessage = redact(command.message());
        Map<String, Object> input = input(command, redactedMessage, serverPlan);
        input.put("_principalRoles", principal.roles());
        input.put("_principalRegions", principal.regionScopes());
        Instant now = Instant.now(clock);
        List<AgentConversationTurn> history = command.sessionId() == null ? List.of()
                : repository.recentConversation(command.sessionId(), principal.actorId(), principal.roles(),
                principal.regionScopes(), now, 6);
        if (history != null && !history.isEmpty()) {
            input.put("conversationHistory", conversationMessages(history));
            AgentConversationTurn last = history.get(history.size() - 1);
            if (last.pendingClarification() && last.intent() != null) {
                input.put("pendingClarification", Map.of("intent", last.intent().name(),
                        "suppliedSlots", last.suppliedSlots(), "missingFields", last.missingFields(),
                        "question", last.assistantMessage()));
            }
        }
        String inputJson = json(input);
        String inputHash = sha256(json(input(command, command.message(), serverPlan)));
        AgentRunRecord run = repository.createRun(command.sessionId(), principal.actorId(), principal.roles(),
                principal.regionScopes(), requestKey, redactedMessage, inputJson, inputHash, traceId,
                serverPlan == null ? intentClassifier.modelId() : "server-defined",
                serverPlan == null ? intentClassifier.promptVersion() : "reassignment-v1", now);
        authorizeRun(run, principal);
        AgentPrincipal effectivePrincipal = new AgentPrincipal(principal.actorId(),
                intersect(principal.roles(), run.roles()), intersect(principal.regionScopes(), run.regionScopes()));
        return new PreparedRun(run, effectivePrincipal);
    }

    public RunSummary summary(UUID runId, AgentPrincipal principal) {
        AgentRunRecord run = run(runId);
        authorizeRun(run, principal);
        return summary(run);
    }

    public List<AgentConversationTurn> conversation(UUID sessionId, AgentPrincipal principal) {
        requireReader(principal);
        return repository.recentConversation(sessionId, principal.actorId(), principal.roles(),
                principal.regionScopes(), Instant.now(clock), 12);
    }

    public List<AgentRunEvent> events(UUID runId, long afterSequence, AgentPrincipal principal) {
        AgentRunRecord run = run(runId);
        authorizeRun(run, principal);
        if (afterSequence < 0 || afterSequence > run.lastSequence()) {
            throw new AgentConflictException("event cursor is outside the run sequence");
        }
        return repository.eventsAfter(runId, afterSequence, 1000);
    }

    public void resume(UUID runId, String traceId) {
        AgentRunRecord run = run(runId);
        if (run.status() != AgentRunStatus.RUNNING) return;
        executeWithRunLease(runId,
                new AgentPrincipal(run.ownerId(), run.roles(), run.regionScopes()), traceId);
    }

    private void executeWithRunLease(UUID runId, AgentPrincipal principal, String traceId) {
        try (AgentExecutionConcurrency.Lease ignored = concurrency.acquireRun(principal.actorId())) {
            executeWithExecutionLease(runId, principal, traceId);
        } catch (RuntimeException failure) {
            failRun(runId, failure, traceId);
        }
    }

    private void executeWithExecutionLease(UUID runId, AgentPrincipal principal, String traceId) {
        executeWithExecutionLease(runId, principal, traceId, null);
    }

    private void executeWithExecutionLease(UUID runId, AgentPrincipal principal, String traceId,
                                           AgentPlan serverPlan) {
        Optional<AgentExecutionConcurrency.Lease> lease = concurrency.tryAcquireExecution(runId.toString());
        if (lease.isEmpty()) return;
        try (AgentExecutionConcurrency.Lease ignored = lease.get()) {
            execute(runId, principal, traceId, serverPlan);
        }
    }

    public int recoverStaleRuns() {
        Instant now = Instant.now(clock);
        List<UUID> ids = repository.recoverableRunIds(now.minus(RECOVERY_AGE), 50);
        int resumed = 0;
        for (UUID id : ids) {
            try {
                resume(id, "agent-recovery-" + id);
                resumed++;
            } catch (RuntimeException exception) {
                failRun(id, exception, "agent-recovery-" + id);
            }
        }
        return resumed;
    }

    @Transactional(noRollbackFor = AgentAccessDeniedException.class)
    public AgentActionRecord approve(UUID actionId, AgentPrincipal principal, String traceId) {
        requireApprover(principal);
        AgentActionRecord action = repository.lockAction(actionId)
                .orElseThrow(() -> new AgentNotFoundException("Agent action was not found"));
        authorizeActionRegion(action, principal, traceId);
        Instant now = Instant.now(clock);
        if (action.status().terminal()) return action;
        if (action.requestedBy().equals(principal.actorId())) {
            denied(principal, action, "SELF_APPROVAL", traceId, now);
            throw new AgentAccessDeniedException("the requester cannot approve their own action");
        }
        if (action.status() != AgentActionStatus.PENDING_CONFIRMATION) {
            throw new AgentConflictException("Agent action is already being resolved");
        }
        if (!action.expiresAt().isAfter(now)) {
            repository.resolveAction(action, AgentActionStatus.EXPIRED, "system", null,
                    "审批已过期，原操作未执行。", traceId, now);
            if (metrics != null) metrics.approval("expired");
            return repository.findAction(actionId).orElseThrow();
        }

        DeliveryOrder order = orders.get(action.targetId());
        if (order.version() != action.expectedVersion() || !validTargetState(action, order.status())) {
            repository.resolveAction(action, AgentActionStatus.INVALIDATED, principal.actorId(), null,
                    "订单状态或版本已变化，原操作未执行，请重新查询。", traceId, now);
            if (metrics != null) metrics.approval("conflict");
            return repository.findAction(actionId).orElseThrow();
        }
        if (!repository.claimAction(actionId, principal.actorId(), now)) {
            AgentActionRecord current = repository.findAction(actionId).orElseThrow();
            if (current.status().terminal()) return current;
            throw new AgentConflictException("Agent action could not be claimed");
        }

        Map<String, Object> params = readMap(action.paramsJson());
        DeliveryOrder changed;
        if ("CANCEL_ORDER".equals(action.actionType())) {
            changed = orders.cancel(action.targetId(), action.expectedVersion(),
                    (String) params.get("reasonCode"), principal.actorId());
        } else if ("RESCHEDULE".equals(action.actionType())) {
            changed = orders.approveReschedule(action.targetId(), action.expectedVersion(),
                    Instant.parse((String) params.get("prepEta")), Instant.parse((String) params.get("deadline")),
                    (String) params.get("reasonCode"), principal.actorId());
        } else if ("REASSIGN_ORDER".equals(action.actionType())) {
            changed = orders.reassign(action.targetId(), action.expectedVersion(),
                    (String) params.get("reasonCode"), principal.actorId());
        } else {
            repository.resolveAction(action, AgentActionStatus.INVALIDATED, principal.actorId(), null,
                    "动作类型不再受支持，原操作未执行。", traceId, now);
            return repository.findAction(actionId).orElseThrow();
        }
        AgentActionRecord claimed = repository.findAction(actionId).orElseThrow();
        String resultRef = "orderId=" + changed.id() + ";status=" + changed.status()
                + ";version=" + changed.version()
                + ("REASSIGN_ORDER".equals(action.actionType()) ? ";newCourierConfirmed=false" : "");
        String completionMessage = "REASSIGN_ORDER".equals(action.actionType())
                ? "改派请求已提交，订单已回到 WAITING_DISPATCH；新骑手尚未确认，需等待后续 Offer/Accept 事件。"
                : "orderId=" + changed.id() + ";version=" + changed.version();
        repository.resolveAction(claimed, AgentActionStatus.COMPLETED, principal.actorId(),
                resultRef, completionMessage, traceId,
                Instant.now(clock));
        if (metrics != null) metrics.approval("completed");
        return repository.findAction(actionId).orElseThrow();
    }

    @Transactional(noRollbackFor = AgentAccessDeniedException.class)
    public AgentActionRecord reject(UUID actionId, String reasonCode, AgentPrincipal principal, String traceId) {
        if (!principal.operationsReader()) throw new AgentAccessDeniedException("operations role is required");
        AgentActionRecord action = repository.lockAction(actionId)
                .orElseThrow(() -> new AgentNotFoundException("Agent action was not found"));
        authorizeActionRegion(action, principal, traceId);
        Instant now = Instant.now(clock);
        if (action.status().terminal()) return action;
        if (!principal.actorId().equals(action.requestedBy()) && !principal.operationsApprover()) {
            denied(principal, action, "REJECT_FORBIDDEN", traceId, now);
            throw new AgentAccessDeniedException("only the requester or an approver may reject this action");
        }
        if (action.status() != AgentActionStatus.PENDING_CONFIRMATION) {
            throw new AgentConflictException("Agent action is already being resolved");
        }
        AgentActionStatus status = action.expiresAt().isAfter(now)
                ? AgentActionStatus.REJECTED : AgentActionStatus.EXPIRED;
        String reason = safeReason(reasonCode);
        String message = status == AgentActionStatus.REJECTED
                ? "高风险操作申请已被拒绝" + (reason.isEmpty() ? "。" : "，原因代码：" + reason + "。")
                : "审批已过期，原操作未执行。";
        repository.resolveAction(action, status, principal.actorId(), null, message, traceId, now);
        if (metrics != null) metrics.approval(status == AgentActionStatus.REJECTED ? "declined" : "expired");
        return repository.findAction(actionId).orElseThrow();
    }

    @Transactional
    public int expireDueActions() {
        Instant now = Instant.now(clock);
        List<UUID> ids = repository.expiredActionIds(now, 100);
        int expired = 0;
        for (UUID id : ids) {
            AgentActionRecord action = repository.lockAction(id).orElse(null);
            if (action != null && action.status() == AgentActionStatus.PENDING_CONFIRMATION
                    && !action.expiresAt().isAfter(now)) {
                repository.resolveAction(action, AgentActionStatus.EXPIRED, "system", null,
                        "审批已过期，原操作未执行。", "agent-action-expiry", now);
                if (metrics != null) metrics.approval("expired");
                expired++;
            }
        }
        return expired;
    }

    private void execute(UUID runId, AgentPrincipal principal, String traceId) {
        execute(runId, principal, traceId, null);
    }

    private void execute(UUID runId, AgentPrincipal principal, String traceId, AgentPlan suppliedServerPlan) {
        AgentRunRecord run = run(runId);
        if (run.status() != AgentRunStatus.RUNNING) return;
        try {
            Map<String, Object> input = readMap(run.inputJson());
            String message = String.valueOf(input.getOrDefault("message", ""));
            String modelId = intentClassifier.modelId();
            String promptVersion = intentClassifier.promptVersion();
            Map<String, Object> saved = run.checkpointJson() == null ? Map.of() : readMap(run.checkpointJson());
            String phase = string(saved.get("phase"));
            if ("TOOL_COMPLETED".equals(phase)) {
                completeFromCheckpoint(runId, run.intent(), saved, traceId);
                return;
            }

            AgentIntent intent;
            AgentIntentClassification classification;
            ToolPlan plan;
            double confidence;
            ToolPlan savedPlan = checkpointPlan(saved);
            boolean restoredPlan = "PLAN_READY".equals(phase)
                    || "TOOL_PENDING".equals(phase) && savedPlan != null;
            if (restoredPlan) {
                intent = run.intent();
                confidence = saved.get("intentConfidence") instanceof Number
                        ? confidence(saved.get("intentConfidence")) : MIN_INTENT_CONFIDENCE;
                plan = savedPlan;
                if (intent == null || plan == null) {
                    throw new AgentConflictException("saved Agent plan is incomplete");
                }
                classification = new AgentIntentClassification(intent, confidence,
                        "RESTORED_CHECKPOINT", List.of());
            } else {
                AgentPlan serverPlan = suppliedServerPlan == null ? serverPlan(input) : suppliedServerPlan;
                PlanProposal proposal = serverPlan == null ? requestPlan(message, planningSlots(input))
                        : new PlanProposal(serverPlan, new AgentIntentClassification(serverPlan.intent(),
                                serverPlan.confidence(), "SERVER_DEFINED_PLAN", List.of()));
                classification = proposal.classification();
                intent = classification.intent();
                confidence = saved.get("intentConfidence") instanceof Number previous
                        ? previous.doubleValue() : classification.confidence();
                AgentIntent continuation = clarificationReplyIntent(input, message);
                if (continuation != null) {
                    intent = continuation;
                    confidence = Math.max(confidence, 0.8);
                    classification = new AgentIntentClassification(intent, confidence,
                            "SESSION_CLARIFICATION_CONTINUATION", List.of());
                    proposal = new PlanProposal(null, classification);
                }
                if (run.intent() != null && run.intent() != intent) {
                    throw new AgentConflictException("model intent changed while recovering the run");
                }
                Map<String, Object> intentCheckpoint = new LinkedHashMap<>();
                intentCheckpoint.put("phase", "INTENT");
                intentCheckpoint.put("model", modelId);
                intentCheckpoint.put("promptVersion", promptVersion);
                intentCheckpoint.put("intentConfidence", confidence);
                intentCheckpoint.put("intentRationale", classification.rationale());
                repository.storeEvent(runId, AgentRunStatus.RUNNING, intent, intentCheckpoint,
                        "intent", "intent", Map.of("intent", intent.name(), "confidence", confidence,
                                "rationale", classification.rationale(), "model", modelId,
                                "promptVersion", promptVersion), Instant.now(clock));

                AgentPlan modelPlan = proposal.plan();
                if (suppliedServerPlan == null && modelPlan != null && !modelPlan.tokenUsage().isZero()) {
                    repository.recordTokenUsage(runId, modelPlan.tokenUsage());
                }
                if (intent == AgentIntent.UNKNOWN || confidence < MIN_INTENT_CONFIDENCE) {
                    clarify(runId, intent, confidence, classification,
                            modelPlan == null ? null : modelPlan.clarificationQuestion(), traceId);
                    return;
                }
                if (modelPlan != null && modelPlan.needsClarification()) {
                    if (modelPlan.toolName() != null || !modelPlan.arguments().isEmpty()) {
                        throw new IllegalStateException("clarification plan must not include a tool call");
                    }
                    clarify(runId, intent, confidence, classification,
                            modelPlan.clarificationQuestion(), traceId);
                    return;
                }
                if (modelPlan != null && (modelPlan.clarificationQuestion() != null
                        || modelPlan.toolName() == null)) {
                    throw new IllegalStateException("tool plan fields are inconsistent");
                }
                plan = modelPlan == null ? plan(intent, input, message)
                        : new ToolPlan(modelPlan.toolName(), modelPlan.arguments(), null);
                if (plan.question() != null) {
                    clarify(runId, intent, confidence, classification, plan.question(), traceId);
                    return;
                }
            }

            if (intent == AgentIntent.UNKNOWN || confidence < MIN_INTENT_CONFIDENCE) {
                clarify(runId, intent, confidence, classification, null, traceId);
                return;
            }
            Set<String> missing = toolGateway.missingRequiredArguments(plan.toolName(), plan.arguments());
            if (!missing.isEmpty()) {
                AgentIntentClassification missingSlots = new AgentIntentClassification(intent, confidence,
                        classification.rationale(), List.copyOf(missing));
                clarify(runId, intent, confidence, missingSlots, questionFor(missing), traceId);
                return;
            }
            Map<String, Object> validatedArguments = toolGateway.validatePlan(runId, intent, plan.toolName(),
                    plan.arguments(), principal, traceId);
            plan = new ToolPlan(plan.toolName(), validatedArguments, null);

            if (!restoredPlan) {
                Map<String, Object> ready = planCheckpoint("PLAN_READY", plan, confidence);
                repository.storeEvent(runId, AgentRunStatus.RUNNING, intent, ready, "plan_ready", "plan_ready",
                        Map.of("intent", intent.name(), "toolName", plan.toolName(),
                                "publicArgsSummary", publicArgs(plan.arguments())), Instant.now(clock));
            }
            String toolName = plan.toolName();
            String toolKey = "primary";
            Map<String, Object> pendingCheckpoint = planCheckpoint("TOOL_PENDING", plan, confidence);
            pendingCheckpoint.put("toolCallKey", toolKey);
            repository.storeEvent(runId, AgentRunStatus.RUNNING, intent, pendingCheckpoint, "tool_start",
                    "tool_start:" + toolKey, Map.of("toolCallId", toolKey, "toolName", toolName,
                            "publicArgsSummary", publicArgs(plan.arguments())), Instant.now(clock));

            Map<String, Object> result = toolGateway.invoke(runId, intent, toolKey, toolName,
                    plan.arguments(), principal, traceId);
            Map<String, Object> resultCheckpoint = new LinkedHashMap<>(pendingCheckpoint);
            resultCheckpoint.put("phase", "TOOL_COMPLETED");
            resultCheckpoint.put("toolResult", result);
            repository.storeEvent(runId, AgentRunStatus.RUNNING, intent, resultCheckpoint, "tool_result",
                    "tool_result:" + toolKey, Map.of("toolCallId", toolKey, "toolName", toolName,
                            "resultSummary", resultSummary(result)), Instant.now(clock));

            if (result.containsKey("actionId")) {
                waitForAction(runId, intent, resultCheckpoint, result, traceId);
                return;
            }

            if ("search_sop".equals(toolName)) appendCitations(runId, intent, resultCheckpoint, result, traceId);
            finish(runId, intent, answer(intent, result), Map.of("phase", "COMPLETED", "result", result), traceId);
        } catch (RuntimeException exception) {
            failRun(runId, exception, traceId);
        }
    }

    private PlanProposal requestPlan(String message, Map<String, Object> slots) {
        long started = System.nanoTime();
        try (AgentExecutionConcurrency.Lease ignored = concurrency.acquireModel(intentClassifier.modelId())) {
            AgentPlan plan = intentClassifier.plan(message, slots);
            AgentIntentClassification classification = plan == null
                    ? intentClassifier.classify(message) : plan.classification();
            if (metrics != null) metrics.modelCall("success", Duration.ofNanos(System.nanoTime() - started));
            return new PlanProposal(plan, classification);
        } catch (RuntimeException failure) {
            if (metrics != null) metrics.modelCall("error", Duration.ofNanos(System.nanoTime() - started));
            throw failure;
        }
    }

    private void completeFromCheckpoint(UUID runId, AgentIntent intent, Map<String, Object> checkpoint,
                                        String traceId) {
        if (intent == null || !(checkpoint.get("toolResult") instanceof Map<?, ?> rawResult)) {
            throw new AgentConflictException("completed tool checkpoint is incomplete");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        rawResult.forEach((key, value) -> result.put(String.valueOf(key), value));
        if (result.containsKey("actionId")) {
            waitForAction(runId, intent, checkpoint, result, traceId);
            return;
        }
        if ("search_sop".equals(checkpoint.get("toolName"))) {
            appendCitations(runId, intent, checkpoint, result, traceId);
        }
        finish(runId, intent, answer(intent, result),
                Map.of("phase", "COMPLETED", "result", result), traceId);
    }

    private void waitForAction(UUID runId, AgentIntent intent, Map<String, Object> checkpoint,
                               Map<String, Object> result, String traceId) {
        AgentActionRecord action = repository.findAction(UUID.fromString((String) result.get("actionId")))
                .orElseThrow(() -> new AgentConflictException("created action could not be loaded"));
        Map<String, Object> waiting = new LinkedHashMap<>(checkpoint);
        waiting.put("phase", "WAITING_CONFIRMATION");
        waiting.put("actionId", action.actionId().toString());
        repository.storeEvent(runId, AgentRunStatus.WAITING_CONFIRMATION, intent, waiting,
                "approval_required", "approval_required",
                Map.of("actionId", action.actionId().toString(), "actionType", action.actionType(),
                        "targetId", action.targetId().toString(), "summary", action.summaryRedacted(),
                        "parameters", readMap(action.paramsJson()), "targetVersion", action.expectedVersion(),
                        "summaryHash", action.summaryHash(), "expiresAt", action.expiresAt().toString()),
                Instant.now(clock));
    }

    private void appendCitations(UUID runId, AgentIntent intent, Map<String, Object> checkpoint,
                                 Map<String, Object> result, String traceId) {
        Object raw = result.get("citations");
        if (!(raw instanceof List<?> citations)) return;
        for (Object value : citations) {
            AgentAnswerRenderer.validatedCitation(value).ifPresent(citation -> {
                repository.storeEvent(runId, AgentRunStatus.RUNNING, intent, checkpoint, "citation",
                        "citation:" + citation.get("citationId"), citation, Instant.now(clock));
            });
        }
    }

    private void finish(UUID runId, AgentIntent intent, String message, Map<String, Object> checkpoint,
                        String traceId) {
        Instant now = Instant.now(clock);
        repository.storeEvent(runId, AgentRunStatus.COMPLETED, intent, checkpoint, "message", "final_message",
                Map.of("channel", "assistant", "content", message), now);
        repository.storeEvent(runId, AgentRunStatus.COMPLETED, intent, checkpoint, "done", "done",
                Map.of("status", AgentRunStatus.COMPLETED.name()), now);
        repository.audit(run(runId).ownerId(), "AGENT_RUN_COMPLETED", "AGENT_RUN", runId.toString(),
                "ALLOWED", "intent=" + intent.name(), traceId, now);
    }

    private void clarify(UUID runId, AgentIntent intent, double confidence,
                         AgentIntentClassification classification, String traceId) {
        clarify(runId, intent, confidence, classification, null, traceId);
    }

    private void clarify(UUID runId, AgentIntent intent, double confidence,
                         AgentIntentClassification classification, String proposedQuestion, String traceId) {
        String question = proposedQuestion == null || proposedQuestion.isBlank()
                ? intent == AgentIntent.UNKNOWN
                    ? "我无法可靠识别这条请求。请说明你要查询订单、查询区域指标、比较高峰方案、检索 SOP，还是申请改派、取消或调整时间。"
                    : "我对请求意图的判断不够确定。请补充或改写请求后再试。"
                : redact(proposedQuestion.trim());
        Map<String, Object> currentInput = readMap(run(runId).inputJson());
        currentInput.putAll(continuationInput(currentInput, string(currentInput.get("message"))));
        List<String> missingFields = clarificationFields(intent, classification, currentInput);
        Map<String, Object> checkpoint = Map.of("phase", "CLARIFICATION", "intentConfidence", confidence,
                "intentRationale", classification.rationale(), "missingFields", missingFields,
                "suppliedSlots", requestSlots(currentInput), "safeFallback", "NO_TOOL_CALL");
        repository.storeEvent(runId, AgentRunStatus.RUNNING, intent, checkpoint,
                "clarification_required", "clarification_required",
                Map.of("question", question, "intent", intent.name(), "confidence", confidence,
                        "threshold", MIN_INTENT_CONFIDENCE, "missingFields", missingFields,
                        "safeFallback", "NO_TOOL_CALL"), Instant.now(clock));
        finish(runId, intent, question, checkpoint, traceId);
    }

    private void failRun(UUID runId, RuntimeException exception, String traceId) {
        AgentRunRecord run = run(runId);
        if (run.status() != AgentRunStatus.RUNNING && run.status() != AgentRunStatus.WAITING_CONFIRMATION) return;
        String safeMessage = exception instanceof AgentAccessDeniedException
                ? "当前账号没有访问该区域或工具的权限。"
                : exception instanceof AgentNotFoundException ? "请求的订单或 Agent 资源不存在。"
                : "Agent 工具执行失败，请重新查询后再试。";
        String code = exception.getClass().getSimpleName().replaceAll("[^A-Za-z0-9_]", "_");
        Instant now = Instant.now(clock);
        Map<String, Object> checkpoint = Map.of("phase", "FAILED", "errorCode", code);
        repository.storeEvent(runId, AgentRunStatus.FAILED, run.intent(), checkpoint, "error", "error",
                Map.of("code", code, "retryable", !(exception instanceof IllegalArgumentException
                        || exception instanceof AgentAccessDeniedException), "safeMessage", safeMessage), now);
        repository.storeEvent(runId, AgentRunStatus.FAILED, run.intent(), checkpoint, "done", "done",
                Map.of("status", AgentRunStatus.FAILED.name()), now);
        repository.audit(run.ownerId(), "AGENT_RUN_FAILED", "AGENT_RUN", runId.toString(), "FAILED",
                "intent=" + run.intent() + ";errorCode=" + code, traceId, now);
    }

    private ToolPlan plan(AgentIntent intent, Map<String, Object> input, String message) {
        input = continuationInput(input, message);
        UUID orderId = uuid(input.get("orderId"));
        if (orderId == null) orderId = mockAgent.orderId(message);
        String regionId = string(input.get("regionId"));
        Map<String, Object> args = new LinkedHashMap<>();
        String tool;
        switch (intent) {
            case REGION_METRICS -> {
                if (regionId == null) return question("请提供有权限访问的 regionId，之后我可以查询该区域指标。");
                tool = "get_region_metrics";
                args.put("regionId", regionId);
                copy(input, args, "from", "to");
                args.put("granularity", input.getOrDefault("granularity", "hour"));
            }
            case PEAK_PLAN_COMPARISON -> {
                if (regionId == null) return question("请提供有权限访问的 regionId，我才能生成区域高峰方案比较。");
                tool = "compare_peak_plans";
                args.put("regionId", regionId);
                copy(input, args, "from", "to", "seed");
                args.putIfAbsent("seed", 0L);
            }
            case ORDER_LOOKUP, ORDER_DIAGNOSIS, DISPATCH_EXPLANATION, DISPATCH_SIMULATION -> {
                if (orderId == null) return question("请提供订单 UUID，我才能读取订单时间线。");
                tool = switch (intent) {
                    case ORDER_LOOKUP -> "get_order_timeline";
                    case ORDER_DIAGNOSIS -> "diagnose_order";
                    case DISPATCH_SIMULATION -> "simulate_dispatch";
                    default -> "explain_dispatch";
                };
                args.put("orderId", orderId.toString());
                if ("get_order_timeline".equals(tool)) args.put("limit", 20);
            }
            case SOP_QA -> {
                if (regionId == null) return question("请提供有权限访问的 regionId，以便按区域检索 SOP。");
                String query = string(input.get("query"));
                tool = "search_sop";
                args.put("query", query == null ? message : query);
                args.put("regionId", regionId);
                args.put("category", input.getOrDefault("category", "SOP"));
            }
            case CANCEL_ORDER, RESCHEDULE, REASSIGN_ORDER -> {
                if (orderId == null) return question("请提供订单 UUID。");
                if (!(input.get("expectedVersion") instanceof Number)) return question("请提供订单当前 expectedVersion，我不会猜测版本。");
                if (string(input.get("reasonCode")) == null) return question("请提供操作原因代码 reasonCode。");
                args.put("orderId", orderId.toString());
                args.put("expectedVersion", input.get("expectedVersion"));
                args.put("reasonCode", input.get("reasonCode"));
                if (intent == AgentIntent.CANCEL_ORDER) {
                    tool = "request_cancel";
                } else if (intent == AgentIntent.REASSIGN_ORDER) {
                    tool = "request_reassignment";
                } else {
                    tool = "request_reschedule";
                    if (string(input.get("prepEta")) == null || string(input.get("deadline")) == null) {
                        return question("请同时提供帶时区的 prepEta 和 deadline；改派申请会绑定当前订单版本。");
                    }
                    args.put("prepEta", input.get("prepEta"));
                    args.put("deadline", input.get("deadline"));
                }
            }
            case UNKNOWN -> {
                return question("我暂时无法确定意图。你可以询问区域指标、订单诊断、派单解释或运营 SOP。");
            }
            default -> throw new IllegalStateException("unsupported intent");
        }
        return new ToolPlan(tool, Map.copyOf(args), null);
    }

    private static ToolPlan checkpointPlan(Map<String, Object> checkpoint) {
        String phase = string(checkpoint.get("phase"));
        Object tool = checkpoint.get("toolName");
        Object rawArgs = checkpoint.get("toolArguments");
        if (("PLAN_READY".equals(phase) || "TOOL_PENDING".equals(phase) || "TOOL_COMPLETED".equals(phase))
                && tool instanceof String toolName && rawArgs instanceof Map<?, ?> values) {
            Map<String, Object> arguments = new LinkedHashMap<>();
            values.forEach((key, value) -> arguments.put(String.valueOf(key), value));
            return new ToolPlan(toolName, Map.copyOf(arguments), null);
        }
        return null;
    }

    private static Map<String, Object> planCheckpoint(String phase, ToolPlan plan, double confidence) {
        Map<String, Object> checkpoint = new LinkedHashMap<>();
        checkpoint.put("phase", phase);
        checkpoint.put("toolName", plan.toolName());
        checkpoint.put("toolArguments", plan.arguments());
        checkpoint.put("intentConfidence", confidence);
        return checkpoint;
    }

    private static double confidence(Object value) {
        if (value instanceof Number number && Double.isFinite(number.doubleValue())
                && number.doubleValue() >= 0 && number.doubleValue() <= 1) {
            return number.doubleValue();
        }
        throw new AgentConflictException("saved Agent confidence is invalid");
    }

    private static Map<String, Object> planningSlots(Map<String, Object> input) {
        Map<String, Object> slots = requestSlots(input);
        if (input.containsKey("conversationHistory")) slots.put("conversationHistory", input.get("conversationHistory"));
        if (input.containsKey("pendingClarification")) {
            slots.put("pendingClarification", input.get("pendingClarification"));
        }
        return Map.copyOf(slots);
    }

    private static Map<String, Object> requestSlots(Map<String, Object> input) {
        Map<String, Object> slots = new LinkedHashMap<>();
        for (String field : List.of("regionId", "orderId", "expectedVersion", "reasonCode", "prepEta",
                "deadline", "from", "to", "category", "seed")) {
            if (input.containsKey(field)) slots.put(field, input.get(field));
        }
        if (!slots.containsKey("orderId")) {
            UUID orderId = new DeterministicMockAgent().orderId(string(input.get("message")));
            if (orderId != null) slots.put("orderId", orderId.toString());
        }
        return slots;
    }

    private static List<String> clarificationFields(AgentIntent intent,
                                                    AgentIntentClassification classification,
                                                    Map<String, Object> input) {
        if (!classification.missingFields().isEmpty()) return classification.missingFields();
        List<String> required = switch (intent) {
            case REGION_METRICS, PEAK_PLAN_COMPARISON -> List.of("regionId");
            case ORDER_LOOKUP, ORDER_DIAGNOSIS, DISPATCH_EXPLANATION, DISPATCH_SIMULATION -> List.of("orderId");
            case SOP_QA -> List.of("regionId");
            case CANCEL_ORDER, REASSIGN_ORDER -> List.of("orderId", "expectedVersion", "reasonCode");
            case RESCHEDULE -> List.of("orderId", "expectedVersion", "reasonCode", "prepEta", "deadline");
            case UNKNOWN -> List.of("intent");
        };
        return required.stream().filter(field -> {
            if (hasInputValue(input.get(field))) return false;
            return !"orderId".equals(field)
                    || new DeterministicMockAgent().orderId(string(input.get("message"))) == null;
        }).toList();
    }

    private static boolean hasInputValue(Object value) {
        return value != null && (!(value instanceof String text) || !text.isBlank());
    }

    private static Map<String, Object> continuationInput(Map<String, Object> input, String message) {
        Map<String, Object> values = new LinkedHashMap<>();
        String trimmed = message == null ? "" : message.trim();
        Object pendingValue = input.get("pendingClarification");
        boolean continuation = clarificationReplyIntent(input, message) != null;
        if (continuation && pendingValue instanceof Map<?, ?> pending
                && pending.get("suppliedSlots") instanceof Map<?, ?> slots) {
            slots.forEach((key, value) -> values.put(String.valueOf(key), value));
        }
        for (String field : List.of("regionId", "orderId", "expectedVersion", "reasonCode", "prepEta",
                "deadline", "from", "to", "category", "seed")) {
            Object current = input.get(field);
            if (current != null && (!(current instanceof String text) || !text.isBlank())) values.put(field, current);
        }
        UUID orderId = uuid(values.get("orderId"));
        if (orderId == null) orderId = new DeterministicMockAgent().orderId(message);
        if (orderId != null) values.put("orderId", orderId.toString());
        if (continuation && pendingValue instanceof Map<?, ?> pending
                && pending.get("missingFields") instanceof List<?> missing) {
            if (missing.contains("regionId") && trimmed.matches("[A-Za-z0-9_-]{1,64}")) {
                values.put("regionId", trimmed);
            }
            if (missing.contains("query") && !trimmed.isBlank()) {
                values.put("query", trimmed);
            }
            if (missing.contains("expectedVersion") && trimmed.matches("[0-9]{1,18}")) {
                try {
                    values.put("expectedVersion", Long.parseLong(trimmed));
                } catch (NumberFormatException ignored) {
                    // Leave the slot missing so the next turn can clarify it.
                }
            }
            if (missing.contains("reasonCode") && trimmed.matches("[A-Z][A-Z0-9_]{1,63}")) {
                values.put("reasonCode", trimmed);
            }
        }
        return values;
    }

    private static AgentIntent clarificationReplyIntent(Map<String, Object> input, String message) {
        Object value = input.get("pendingClarification");
        if (!(value instanceof Map<?, ?> pending) || !(pending.get("intent") instanceof String intentName)
                || !(pending.get("missingFields") instanceof List<?> missing)) return null;
        AgentIntent intent;
        try {
            intent = AgentIntent.valueOf(intentName);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
        String trimmed = message == null ? "" : message.trim();
        if (missing.contains("orderId")) {
            UUID found = new DeterministicMockAgent().orderId(trimmed);
            if (found != null && trimmed.replace(found.toString(), "").matches("[\\s,:=]*")) return intent;
        }
        if (missing.contains("expectedVersion") && trimmed.matches("[0-9]{1,18}")) return intent;
        if (missing.contains("reasonCode") && trimmed.matches("[A-Z][A-Z0-9_]{1,63}")) return intent;
        if (missing.contains("regionId") && trimmed.matches("[A-Za-z0-9_-]{1,64}")) return intent;
        if (missing.contains("query") && !trimmed.isBlank()) {
            AgentIntent current = new DeterministicMockAgent().classify(trimmed).intent();
            if (current == AgentIntent.UNKNOWN || current == intent) return intent;
        }
        return null;
    }

    private static List<Map<String, String>> conversationMessages(List<AgentConversationTurn> turns) {
        List<Map<String, String>> messages = new ArrayList<>();
        for (AgentConversationTurn turn : turns) {
            messages.add(Map.of("role", "user", "content", truncate(turn.userMessage(), 1600)));
            messages.add(Map.of("role", "assistant", "content", truncate(turn.assistantMessage(), 1600)));
        }
        return List.copyOf(messages);
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) return value == null ? "" : value;
        return value.substring(0, maxLength);
    }

    private static String questionFor(Set<String> missing) {
        if (missing.contains("regionId")) return "请提供有权限访问的 regionId。";
        if (missing.contains("orderId")) return "请提供订单 UUID。";
        if (missing.contains("expectedVersion")) return "请提供订单当前 expectedVersion，我不会猜测版本。";
        if (missing.contains("reasonCode")) return "请提供操作原因代码 reasonCode。";
        if (missing.contains("prepEta") || missing.contains("deadline")) {
            return "请同时提供带时区的 prepEta 和 deadline。";
        }
        if (missing.contains("query")) return "请补充要检索的 SOP 主题或问题。";
        if (missing.contains("to") || missing.contains("from")) return "请同时提供完整的 from 和 to 时间范围。";
        return "请补充缺少的信息后再试。";
    }

    private String answer(AgentIntent intent, Map<String, Object> result) {
        return AgentAnswerRenderer.render(intent, result, mapper);
    }

    private static String publicArgs(Map<String, Object> args) {
        Map<String, Object> safe = new LinkedHashMap<>();
        args.forEach((key, value) -> safe.put(key, key.equals("query") ? "[已隐藏]" : value));
        return safe.toString();
    }

    private static String resultSummary(Map<String, Object> result) {
        if (result.containsKey("actionId")) return "actionId=" + result.get("actionId");
        if (result.containsKey("status")) return "status=" + result.get("status");
        return "fields=" + result.keySet();
    }

    private static ToolPlan question(String text) {
        return new ToolPlan(null, Map.of(), text);
    }

    private static boolean validTargetState(AgentActionRecord action, OrderStatus status) {
        if ("CANCEL_ORDER".equals(action.actionType())) {
            return status != OrderStatus.DELIVERED && status != OrderStatus.CANCELLED;
        }
        if ("REASSIGN_ORDER".equals(action.actionType())) {
            return status == OrderStatus.OFFERED || status == OrderStatus.ACCEPTED
                    || status == OrderStatus.OPS_ESCALATED;
        }
        return status == OrderStatus.WAITING_DISPATCH || status == OrderStatus.OFFERED
                || status == OrderStatus.ACCEPTED || status == OrderStatus.OPS_ESCALATED;
    }

    private void authorizeActionRegion(AgentActionRecord action, AgentPrincipal principal, String traceId) {
        String region = toolGateway.orderRegion(action.targetId());
        if (region == null) throw new AgentNotFoundException("order was not found");
        if (!principal.canAccessRegion(region)) {
            repository.audit(principal.actorId(), "AGENT_ACTION_ACCESS", "AGENT_ACTION", action.actionId().toString(),
                    "DENIED", "region=" + region, traceId, Instant.now(clock));
            throw new AgentAccessDeniedException("action is outside the authenticated user's region scope");
        }
    }

    private void denied(AgentPrincipal principal, AgentActionRecord action, String reason, String traceId, Instant now) {
        repository.audit(principal.actorId(), "AGENT_ACTION_APPROVAL", "AGENT_ACTION", action.actionId().toString(),
                "DENIED", "reason=" + reason + ";runId=" + action.runId(), traceId, now);
    }

    private void authorizeRun(AgentRunRecord run, AgentPrincipal principal) {
        if (run.ownerId().equals(principal.actorId()) || principal.administrator()) return;
        boolean overlap = run.regionScopes().stream().anyMatch(principal.regionScopes()::contains);
        if (!principal.operationsReader() || !overlap) {
            repository.audit(principal.actorId(), "AGENT_RUN_ACCESS", "AGENT_RUN", run.runId().toString(),
                    "DENIED", "run owner or region scope mismatch", run.traceId(), Instant.now(clock));
            throw new AgentAccessDeniedException("Agent run is not accessible to this identity");
        }
    }

    private AgentRunRecord run(UUID runId) {
        return repository.findRun(runId).orElseThrow(() -> new AgentNotFoundException("Agent run was not found"));
    }

    private RunSummary summary(AgentRunRecord run) {
        Map<String, Object> checkpoint = run.checkpointJson() == null ? Map.of() : readMap(run.checkpointJson());
        String actionId = checkpoint.get("actionId") == null ? null : String.valueOf(checkpoint.get("actionId"));
        String message = checkpoint.get("answer") == null ? null : String.valueOf(checkpoint.get("answer"));
        if (message == null && run.status().terminal()) {
            List<AgentRunEvent> events = repository.eventsAfter(run.runId(), Math.max(0, run.lastSequence() - 3), 10);
            message = events.stream().filter(event -> "message".equals(event.eventType()))
                    .map(event -> String.valueOf(event.payload().get("content"))).findFirst().orElse(null);
        }
        return new RunSummary(run.runId(), run.sessionId(), run.status(), run.intent(), run.lastSequence(),
                run.modelId(), new TokenUsageSummary(run.tokenUsage().inputTokens(), run.tokenUsage().outputTokens(),
                        run.tokenUsage().totalTokens()), actionId, message, run.startedAt(), run.updatedAt(),
                run.endedAt());
    }

    private void requireReader(AgentPrincipal principal) {
        if (!principal.operationsReader()) throw new AgentAccessDeniedException("operations role is required");
    }

    private void requireApprover(AgentPrincipal principal) {
        if (!principal.operationsApprover()) throw new AgentAccessDeniedException("operations approver role is required");
    }

    private static void validateCommand(ChatCommand command, String requestKey) {
        if (command == null || command.message() == null || command.message().isBlank()
                || command.message().length() > 4000) throw new IllegalArgumentException("message must be 1 to 4000 characters");
        if (requestKey == null || !requestKey.matches("[A-Za-z0-9._:-]{8,128}")) {
            throw new IllegalArgumentException("a valid Idempotency-Key is required");
        }
        if (command.regionId() != null && !command.regionId().matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("regionId is invalid");
        }
        if (command.category() != null && !command.category().matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("category is invalid");
        }
    }

    private static Map<String, Object> input(ChatCommand command, String message, AgentPlan serverPlan) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("message", message);
        put(values, "regionId", command.regionId());
        put(values, "orderId", command.orderId() == null ? null : command.orderId().toString());
        put(values, "expectedVersion", command.expectedVersion());
        put(values, "reasonCode", command.reasonCode());
        put(values, "prepEta", command.prepEta() == null ? null : command.prepEta().toString());
        put(values, "deadline", command.deadline() == null ? null : command.deadline().toString());
        put(values, "from", command.from() == null ? null : command.from().toString());
        put(values, "to", command.to() == null ? null : command.to().toString());
        put(values, "category", command.category());
        put(values, "seed", command.seed());
        put(values, "sessionId", command.sessionId() == null ? null : command.sessionId().toString());
        put(values, "serverPlan", serverPlan);
        return values;
    }

    private AgentPlan serverPlan(Map<String, Object> input) {
        Object value = input.get("serverPlan");
        if (value == null) return null;
        try {
            return mapper.convertValue(value, AgentPlan.class);
        } catch (IllegalArgumentException exception) {
            throw new AgentConflictException("stored server plan is invalid");
        }
    }

    private static void put(Map<String, Object> values, String key, Object value) {
        if (value != null) values.put(key, value);
    }

    private static void copy(Map<String, Object> input, Map<String, Object> output, String... keys) {
        for (String key : keys) if (input.containsKey(key)) output.put(key, input.get(key));
    }

    private static UUID uuid(Object value) {
        if (value instanceof UUID id) return id;
        if (value instanceof String text) {
            try {
                return UUID.fromString(text);
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String string(Object value) {
        return value instanceof String text && !text.isBlank() ? text.trim() : null;
    }

    private static String safeReason(String reasonCode) {
        return reasonCode != null && reasonCode.matches("[A-Z][A-Z0-9_]{1,63}") ? reasonCode : "";
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("could not serialize Agent state", exception);
        }
    }

    private Map<String, Object> readMap(String value) {
        try {
            return mapper.readValue(value, new TypeReference<>() { });
        } catch (Exception exception) {
            throw new AgentConflictException("stored Agent checkpoint is invalid");
        }
    }

    private static String redact(String value) {
        return SensitiveDataRedactor.redact(value);
    }

    private static Set<String> intersect(Set<String> first, Set<String> second) {
        java.util.HashSet<String> result = new java.util.HashSet<>(first);
        result.retainAll(second);
        return Set.copyOf(result);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public record ChatCommand(String message, UUID sessionId, String regionId, UUID orderId,
                              Long expectedVersion, String reasonCode, Instant prepEta, Instant deadline,
                              Instant from, Instant to, String category, Long seed) {
        public ChatCommand(String message, UUID sessionId, String regionId, UUID orderId,
                           Long expectedVersion, String reasonCode, Instant prepEta, Instant deadline,
                           Instant from, Instant to) {
            this(message, sessionId, regionId, orderId, expectedVersion, reasonCode, prepEta, deadline,
                    from, to, null, null);
        }

        public ChatCommand(String message, UUID sessionId, String regionId, UUID orderId,
                           Long expectedVersion, String reasonCode, Instant prepEta, Instant deadline,
                           Instant from, Instant to, String category) {
            this(message, sessionId, regionId, orderId, expectedVersion, reasonCode, prepEta, deadline,
                    from, to, category, null);
        }
    }

    public record RunSummary(UUID runId, UUID sessionId, AgentRunStatus status, AgentIntent intent,
                             long lastSequence, String modelId, TokenUsageSummary tokenUsage, String actionId,
                             String summary, Instant startedAt, Instant updatedAt, Instant endedAt) {
    }

    public record TokenUsageSummary(long inputTokens, long outputTokens, long totalTokens) {
    }

    private record ToolPlan(String toolName, Map<String, Object> arguments, String question) {
    }

    private record PreparedRun(AgentRunRecord run, AgentPrincipal principal) {
    }

    private record PlanProposal(AgentPlan plan, AgentIntentClassification classification) {
    }
}
