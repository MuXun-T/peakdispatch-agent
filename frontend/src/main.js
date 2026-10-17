const state = { username: '', password: '', roles: [], policies: [], documents: [], agentSessionId: null,
  activeRunId: null, runSequence: 0, agentBusy: false };
const $ = (selector) => document.querySelector(selector);
const $$ = (selector) => [...document.querySelectorAll(selector)];

function showNotice(message, isError = false) {
  const notice = $('#notice');
  notice.textContent = message;
  notice.classList.toggle('error', isError);
  notice.hidden = !message;
}

function apiUrl(path) {
  return path;
}

async function api(path, options = {}) {
  if (!state.username || !state.password) throw new Error('Connect with an operations account first.');
  const method = (options.method || 'GET').toUpperCase();
  const headers = new Headers(options.headers || {});
  headers.set('Authorization', `Basic ${btoa(`${state.username}:${state.password}`)}`);
  if (options.body) headers.set('Content-Type', 'application/json');
  if (!['GET', 'HEAD'].includes(method)) headers.set('Idempotency-Key', options.idempotencyKey || crypto.randomUUID());
  const response = await fetch(apiUrl(path), { ...options, method, headers });
  const payload = await response.json().catch(() => ({}));
  if (!response.ok) {
    if (response.status === 401) $('#connection-state').textContent = 'Authentication failed';
    throw new Error(payload.error?.message || `Request failed (${response.status})`);
  }
  return payload.data;
}

function authHeaders(idempotencyKey) {
  if (!state.username || !state.password) throw new Error('Connect with an operations account first.');
  return new Headers({
    Authorization: `Basic ${btoa(`${state.username}:${state.password}`)}`,
    'Content-Type': 'application/json',
    'Idempotency-Key': idempotencyKey || crypto.randomUUID()
  });
}

function appendAgentEvent(type, payload) {
  const list = $('#agent-events');
  if (list.querySelector('.empty-state')) list.replaceChildren();
  const item = document.createElement('article');
  item.className = `agent-event event-${type}`;
  const title = document.createElement('strong');
  title.textContent = ({ intent: 'Intent', tool_start: 'Tool started', tool_result: 'Tool result',
    clarification_required: 'Clarification', approval_required: 'Approval required',
    citation: 'Source', message: 'Answer', error: 'Error', done: 'Run complete' })[type] || type;
  const detail = document.createElement('pre');
  detail.textContent = JSON.stringify(payload, null, 2);
  item.append(title, detail);
  list.append(item);
  if (type === 'done') $('#agent-run-state').textContent = payload.status || 'Completed';
  else if (type === 'approval_required') $('#agent-run-state').textContent = 'Waiting for approval';
  else if (type === 'error') $('#agent-run-state').textContent = 'Failed';
  else $('#agent-run-state').textContent = 'Running';
}

function renderAgentAnswer(content) {
  const message = document.createElement('article');
  message.className = 'agent-message assistant-message';
  const label = document.createElement('strong'); label.textContent = 'Agent';
  const text = document.createElement('p'); text.textContent = content || '';
  message.append(label, text);
  $('#agent-messages').append(message);
}

function renderAgentCitations(citations) {
  (citations || []).forEach((citation) => {
    const item = document.createElement('article'); item.className = 'citation-item';
    const heading = document.createElement('div'); heading.className = 'citation-heading';
    const title = document.createElement('strong'); title.textContent = citation.title || 'Source';
    const version = document.createElement('span'); version.textContent = `v${citation.version || '?'}`;
    heading.append(title, version);
    const source = document.createElement(/^https?:\/\//i.test(citation.uri || '') ? 'a' : 'span');
    source.className = 'citation-source';
    source.textContent = `${citation.section || 'General'} · ${citation.uri || ''}`;
    if (source instanceof HTMLAnchorElement) {
      source.href = citation.uri; source.target = '_blank'; source.rel = 'noopener noreferrer';
    }
    const excerpt = document.createElement('p'); excerpt.textContent = citation.excerpt || '';
    item.append(heading, source, excerpt); $('#agent-messages').append(item);
  });
}

function renderApproval(payload) {
  const item = document.createElement('article'); item.className = 'agent-approval';
  const title = document.createElement('strong'); title.textContent = payload.actionType || 'Pending action';
  const summary = document.createElement('p'); summary.textContent = payload.summary || '';
  const facts = document.createElement('pre');
  facts.textContent = JSON.stringify({ targetId: payload.targetId, targetVersion: payload.targetVersion,
    parameters: payload.parameters, expiresAt: payload.expiresAt }, null, 2);
  item.append(title, summary, facts);
  if (state.roles.includes('ROLE_OPS_APPROVER') || state.roles.includes('ROLE_OPS_ADMIN')) {
    const actions = document.createElement('div'); actions.className = 'approval-actions';
    const approve = document.createElement('button'); approve.type = 'button'; approve.className = 'button button-primary';
    approve.textContent = 'Approve'; approve.addEventListener('click', () => resolveAgentAction(payload.actionId, 'approve', item));
    const reject = document.createElement('button'); reject.type = 'button'; reject.className = 'button button-secondary';
    reject.textContent = 'Reject'; reject.addEventListener('click', () => resolveAgentAction(payload.actionId, 'reject', item));
    actions.append(approve, reject); item.append(actions);
  }
  $('#agent-messages').append(item);
}

function renderAgentFrame(type, payload) {
  const sequence = Number(payload.sequence || 0);
  if (sequence > 0 && sequence <= state.runSequence) return;
  state.runSequence = Math.max(state.runSequence, sequence);
  appendAgentEvent(type, payload);
  if (type === 'message' && payload.channel === 'assistant') renderAgentAnswer(payload.content);
  if (type === 'citation') renderAgentCitations([payload]);
  if (type === 'approval_required') renderApproval(payload);
  $('#agent-messages').scrollTop = $('#agent-messages').scrollHeight;
}

async function readAgentStream(response) {
  if (!response.ok) {
    const payload = await response.json().catch(() => ({}));
    throw new Error(payload.error?.message || `Request failed (${response.status})`);
  }
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  while (true) {
    const { value, done } = await reader.read();
    buffer += decoder.decode(value || new Uint8Array(), { stream: !done });
    let boundary;
    while ((boundary = buffer.indexOf('\n\n')) >= 0) {
      const frame = buffer.slice(0, boundary); buffer = buffer.slice(boundary + 2);
      const event = frame.match(/^event: (.+)$/m)?.[1];
      const data = frame.match(/^data: (.+)$/m)?.[1];
      if (event && data) renderAgentFrame(event, JSON.parse(data));
    }
    if (done) break;
  }
}

async function restoreAgentSession() {
  if (!state.agentSessionId) return;
  $('#agent-session-label').textContent = `Conversation ${state.agentSessionId}`;
  if (!state.username || $('#agent-messages').dataset.loadedSession === state.agentSessionId) return;
  try {
    const turns = await api(`/api/v1/ops/sessions/${state.agentSessionId}/turns`);
    const messages = $('#agent-messages'); messages.replaceChildren();
    turns.forEach((turn) => {
      const user = document.createElement('article'); user.className = 'agent-message user-message';
      const userLabel = document.createElement('strong'); userLabel.textContent = state.username;
      const userText = document.createElement('p'); userText.textContent = turn.userMessage;
      user.append(userLabel, userText); messages.append(user);
      renderAgentAnswer(turn.assistantMessage);
    });
    if (!turns.length) messages.append(emptyState('Start a conversation.'));
    messages.dataset.loadedSession = state.agentSessionId;
  } catch (error) {
    showNotice(error.message, true);
  }
}

async function runAgentChat(event) {
  event.preventDefault();
  if (state.agentBusy) return;
  const message = $('#agent-message').value.trim();
  if (!message) return;
  const userMessage = document.createElement('article'); userMessage.className = 'agent-message user-message';
  const label = document.createElement('strong'); label.textContent = state.username;
  const text = document.createElement('p'); text.textContent = message;
  userMessage.append(label, text); $('#agent-messages').append(userMessage);
  $('#agent-message').value = '';
  state.agentBusy = true; $('#agent-send').disabled = true; $('#agent-run-state').textContent = 'Starting';
  $('#agent-events').replaceChildren(); state.runSequence = 0;
  try {
    const response = await fetch('/api/v1/ops/chat', { method: 'POST', headers: authHeaders(),
      body: JSON.stringify({ message, sessionId: state.agentSessionId, regionId: region() }) });
    const sessionId = response.headers.get('X-Agent-Session-Id');
    const runId = response.headers.get('X-Agent-Run-Id');
    if (sessionId) {
      state.agentSessionId = sessionId; sessionStorage.setItem('peakdispatch-agent-session', sessionId);
      $('#agent-session-label').textContent = `Conversation ${sessionId}`;
      $('#agent-messages').dataset.loadedSession = sessionId;
    }
    state.activeRunId = runId;
    await readAgentStream(response);
    if (runId) await drainAgentRun(runId);
  } catch (error) {
    showNotice(error.message, true); $('#agent-run-state').textContent = 'Failed';
  } finally {
    state.agentBusy = false; $('#agent-send').disabled = false; $('#agent-message').focus();
  }
}

async function resolveAgentAction(actionId, outcome, item) {
  const buttonSet = item.querySelectorAll('button'); buttonSet.forEach((button) => { button.disabled = true; });
  try {
    const body = outcome === 'reject' ? JSON.stringify({ reasonCode: 'OPS_REVIEW' }) : undefined;
    const result = await api(`/api/v1/ops/actions/${actionId}/${outcome}`,
      { method: 'POST', ...(body ? { body } : {}) });
    const status = document.createElement('p'); status.className = 'approval-result'; status.textContent = result.status;
    item.append(status);
    if (state.activeRunId) await resumeAgentEvents(state.activeRunId);
  } catch (error) {
    buttonSet.forEach((button) => { button.disabled = false; }); showNotice(error.message, true);
  }
}

async function drainAgentRun(runId) {
  while (true) {
    const current = await api(`/api/v1/ops/runs/${runId}`);
    if (['COMPLETED', 'FAILED', 'CANCELLED'].includes(current.status)) return;
    try {
      await resumeAgentEvents(runId);
    } catch (error) {
      if (!(error instanceof TypeError)) throw error;
      await new Promise((resolve) => setTimeout(resolve, 1000));
    }
  }
}

async function resumeAgentEvents(runId = state.activeRunId) {
  if (!runId) return;
  const headers = authHeaders(); headers.delete('Content-Type'); headers.delete('Idempotency-Key');
  headers.set('Last-Event-ID', `${runId}:${state.runSequence}`);
  const response = await fetch(`/api/v1/ops/runs/${runId}/events`, { headers });
  await readAgentStream(response);
}

function newAgentSession() {
  state.agentSessionId = null; state.activeRunId = null; state.runSequence = 0;
  sessionStorage.removeItem('peakdispatch-agent-session');
  $('#agent-session-label').textContent = 'New conversation';
  $('#agent-run-state').textContent = 'Idle';
  $('#agent-messages').replaceChildren(emptyState('Start a conversation.'));
  delete $('#agent-messages').dataset.loadedSession;
  $('#agent-events').replaceChildren(emptyState('Agent events will appear here.'));
}

function region() {
  return $('#region-id').value.trim();
}

function queryRegion() {
  return `regionId=${encodeURIComponent(region())}`;
}

function setView(view) {
  $$('.nav-link').forEach((button) => button.classList.toggle('active', button.dataset.view === view));
  $$('.view-panel').forEach((panel) => { panel.hidden = panel.id !== `view-${view}`; });
  const title = ({ dashboard: 'Dashboard', timeline: 'Timeline', policies: 'Dispatch policies', replays: 'Simulation replays', knowledge: 'Knowledge', agent: 'Agent' })[view];
  $('#view-title').textContent = title;
  $('#page-heading').textContent = view === 'dashboard' ? 'Operations overview' : title;
  if (view === 'policies') loadPolicies();
  if (view === 'knowledge') loadKnowledge();
  if (view === 'agent') restoreAgentSession();
}

function number(value) {
  return Number(value || 0).toLocaleString();
}

function setMetrics(snapshot) {
  const cards = [
    ['Orders', snapshot.orders, 'lifecycle volume', 'metric-green'],
    ['Dispatch attempts', snapshot.dispatchAttempts, `${number(snapshot.dispatchFailures)} failed`, 'metric-blue'],
    ['Quotes created', snapshot.offersCreated, `${number(snapshot.activeOffers)} active`, 'metric-violet'],
    ['Timed out', snapshot.offerTimedOut, `${number(snapshot.offerRejected)} rejected`, 'metric-amber'],
    ['Escalations', snapshot.escalations, 'operations handoff', 'metric-red'],
    ['Online couriers', snapshot.onlineCouriers, `${number(snapshot.capacityLimit)} total slots`, 'metric-teal']
  ];
  $('#metrics-grid').replaceChildren(...cards.map(([label, value, note, color]) => {
    const card = document.createElement('article');
    card.className = `metric ${color}`;
    const caption = document.createElement('span'); caption.textContent = label;
    const amount = document.createElement('strong'); amount.textContent = number(value);
    const detail = document.createElement('small'); detail.textContent = note;
    card.append(caption, amount, detail);
    return card;
  }));
  const lifecycle = [
    ['Waiting dispatch', snapshot.waitingDispatch, 'state-waiting'],
    ['Offered', snapshot.offered, 'state-offered'],
    ['Accepted', snapshot.accepted, 'state-accepted'],
    ['Picked up', snapshot.pickedUp, 'state-picked'],
    ['Delivered', snapshot.delivered, 'state-delivered'],
    ['Cancelled', snapshot.cancelled, 'state-cancelled']
  ];
  $('#lifecycle-list').replaceChildren(...lifecycle.map(([label, value, style]) => {
    const row = document.createElement('div'); row.className = 'lifecycle-row';
    const name = document.createElement('span'); name.innerHTML = `<i class="${style}"></i>`; name.append(document.createTextNode(label));
    const count = document.createElement('strong'); count.textContent = number(value);
    row.append(name, count); return row;
  }));
  const capacity = Math.max(0, snapshot.capacityLimit || 0);
  const committed = Math.max(0, snapshot.committedCapacity || 0);
  const reserved = Math.max(0, snapshot.reservedCapacity || 0);
  const available = Math.max(0, capacity - committed - reserved);
  $('#capacity-total').textContent = number(available);
  $('#capacity-committed').textContent = number(committed);
  $('#capacity-reserved').textContent = number(reserved);
  $('#capacity-free').textContent = number(available);
  $('#capacity-used').style.width = `${capacity ? Math.min(100, ((committed + reserved) / capacity) * 100) : 0}%`;
  $('#region-meta').textContent = snapshot.regionId;
  $('#snapshot-meta').textContent = new Date(snapshot.asOf).toLocaleString();
  $('#window-meta').textContent = `${new Date(snapshot.from).toLocaleString()} — ${new Date(snapshot.to).toLocaleString()}`;
}

async function loadDashboard() {
  const from = new Date($('#from-date').value).toISOString();
  const to = new Date($('#to-date').value).toISOString();
  const data = await api(`/api/v1/ops/dashboard?${queryRegion()}&from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`);
  setMetrics(data);
  showNotice('Dashboard refreshed.');
}

function tableCell(text, className = '') {
  const cell = document.createElement('td');
  cell.textContent = text == null ? '—' : String(text);
  if (className) cell.className = className;
  return cell;
}

async function loadTimeline(event) {
  event?.preventDefault();
  const id = $('#timeline-order-id').value.trim();
  const rows = await api(`/api/v1/ops/orders/${encodeURIComponent(id)}/timeline`);
  const body = $('#timeline-rows'); body.replaceChildren();
  if (!rows.length) { body.append(tableEmpty(6, 'No timeline entries.')); return; }
  rows.forEach((entry) => {
    const row = document.createElement('tr');
    row.append(tableCell(new Date(entry.occurredAt).toLocaleString()), tableCell(entry.type),
      tableCell(entry.source), tableCell(entry.actorId), tableCell(entry.version), tableCell(entry.details, 'details-cell'));
    body.append(row);
  });
}

function tableEmpty(columns, text) {
  const row = document.createElement('tr');
  const cell = tableCell(text, 'empty-cell'); cell.colSpan = columns; row.append(cell); return row;
}

function policyPayload() {
  const config = JSON.parse($('#policy-json').value);
  return { regionId: region(), ...config };
}

async function loadPolicies() {
  const policies = await api(`/api/v1/dispatch/policies?${queryRegion()}`);
  state.policies = policies;
  const active = policies.find((policy) => policy.status === 'ACTIVE');
  $('#active-policy-label').textContent = active ? `Active: v${active.version}` : 'No active policy';
  const body = $('#policy-rows'); body.replaceChildren();
  if (!policies.length) { body.append(tableEmpty(4, 'No policy versions.')); return; }
  policies.forEach((policy) => {
    const row = document.createElement('tr');
    row.append(tableCell(`v${policy.version}`, 'version-cell'), tableCell(policy.status),
      tableCell(policy.createdBy || '—'));
    const actions = document.createElement('td'); actions.className = 'actions-cell';
    if (policy.status === 'DRAFT') {
      actions.append(actionButton('Validate', 'validate-policy', policy.policyId), actionButton('Activate', 'activate-policy', policy.policyId));
    } else if (policy.status === 'ACTIVE') {
      actions.append(actionButton('Deactivate', 'deactivate-policy', policy.policyId));
    }
    row.append(actions); body.append(row);
  });
}

function actionButton(label, action, id) {
  const button = document.createElement('button');
  button.type = 'button'; button.className = 'text-action'; button.textContent = label;
  button.dataset.action = action; button.dataset.id = id; return button;
}

async function policyAction(event) {
  const button = event.target.closest('[data-action]');
  if (!button) return;
  const { action, id } = button.dataset;
  if (action === 'validate-policy') {
    const result = await api(`/api/v1/dispatch/policies/${id}/validate`, { method: 'POST', body: '{}' });
    showNotice(result.valid ? `Policy v${result.version} is valid.` : result.errors.join('; '), !result.valid);
  } else {
    const suffix = action === 'activate-policy' ? 'activate' : 'deactivate';
    await api(`/api/v1/dispatch/policies/${id}/${suffix}`, { method: 'POST', body: '{}' });
    showNotice(`Policy ${suffix}d.`);
    await loadPolicies();
  }
}

async function createPolicy() {
  const result = await api('/api/v1/dispatch/policies', { method: 'POST', body: JSON.stringify(policyPayload()) });
  showNotice(`Draft v${result.version} saved.`);
  await loadPolicies();
}

async function rollbackPolicy(event) {
  event.preventDefault();
  const version = Number($('#rollback-version').value);
  const result = await api(`/api/v1/dispatch/policies/regions/${encodeURIComponent(region())}/rollback`, {
    method: 'POST', body: JSON.stringify({ version })
  });
  showNotice(`Rollback created as v${result.version}.`);
  await loadPolicies();
}

async function runReplay(event) {
  event.preventDefault();
  const request = {
    regionId: region(), scenarioId: $('#scenario-id').value.trim(), seed: Number($('#replay-seed').value),
    orderCount: Number($('#order-count').value), courierCount: Number($('#courier-count').value)
  };
  const replay = await api('/api/v1/ops/simulations/replays', { method: 'POST', body: JSON.stringify(request) });
  $('#compare-first').value = replay.replayId;
  renderReplay(replay);
  showNotice(`Replay ${replay.replayId} completed.`);
}

function renderReplay(replay) {
  const report = replay.report || replay;
  const wrap = document.createElement('div'); wrap.className = 'replay-report';
  const summary = document.createElement('div'); summary.className = 'replay-summary';
  [[`Replay ${replay.replayId}`, 'Replay ID'], [`${number(report.assignedOrders)} / ${number(report.orderCount)}`, 'Assigned'],
    [number(report.failedOrders), 'Failed'], [`${(report.assignmentRate * 100).toFixed(1)}%`, 'Assignment rate'],
    [`${Number(report.averageEtaSeconds).toFixed(0)} sec`, 'Average ETA'], [replay.resultHash || '—', 'Result hash']].forEach(([value, label]) => {
      const item = document.createElement('div'); item.className = 'replay-stat';
      const strong = document.createElement('strong'); strong.textContent = value;
      const small = document.createElement('span'); small.textContent = label; item.append(strong, small); summary.append(item);
    });
  const details = document.createElement('details');
  const title = document.createElement('summary'); title.textContent = 'Order decisions';
  const pre = document.createElement('pre'); pre.textContent = JSON.stringify(report.decisions || [], null, 2);
  details.append(title, pre); wrap.append(summary, details); $('#replay-output').replaceChildren(wrap);
}

async function compareReplays(event) {
  event.preventDefault();
  const result = await api('/api/v1/ops/simulations/replays/compare', { method: 'POST', body: JSON.stringify({
    firstReplayId: $('#compare-first').value.trim(), secondReplayId: $('#compare-second').value.trim()
  }) });
  $('#compare-result').textContent = result.identical ? 'Identical results' : `Assigned delta ${result.assignedDelta}; ETA delta ${result.averageEtaDeltaSeconds.toFixed(0)} sec`;
}

async function loadKnowledge() {
  const [documents, index] = await Promise.all([
    api(`/api/v1/knowledge/documents?${queryRegion()}`), api(`/api/v1/knowledge/index?${queryRegion()}`)
  ]);
  state.documents = documents;
  $('#index-meta').textContent = index ? `Index v${index.indexVersion} · ${index.physicalIndex} · ${number(index.chunkCount)} chunks · activated ${new Date(index.activatedAt).toLocaleString()}` : 'No active index';
  renderDocuments();
}

function renderDocuments() {
  const list = $('#document-list'); list.replaceChildren();
  if (!state.documents.length) { list.append(emptyState('No active documents in this region.')); return; }
  state.documents.forEach((knowledgeDocument) => {
    const item = document.createElement('article'); item.className = 'document-item';
    const content = document.createElement('div');
    const title = document.createElement('strong'); title.textContent = knowledgeDocument.title;
    const meta = document.createElement('span'); meta.textContent = `${knowledgeDocument.externalKey} · v${knowledgeDocument.version} · ${knowledgeDocument.category}`;
    content.append(title, meta);
    const button = actionButton('Deactivate', 'deactivate-document', knowledgeDocument.id); button.classList.add('danger-action');
    item.append(content, button); list.append(item);
  });
}

function emptyState(text) {
  const element = document.createElement('div'); element.className = 'empty-state'; element.textContent = text; return element;
}

async function deactivateDocument(event) {
  const button = event.target.closest('[data-action="deactivate-document"]');
  if (!button) return;
  await api(`/api/v1/knowledge/documents/${button.dataset.id}/deactivate`, { method: 'POST', body: '{}' });
  showNotice('Document deactivated and index rebuilt.');
  await loadKnowledge();
}

async function searchKnowledge(event) {
  event.preventDefault();
  const q = encodeURIComponent($('#knowledge-query').value.trim());
  const result = await api(`/api/v1/knowledge/search?q=${q}&${queryRegion()}&limit=8`);
  const list = $('#citation-list'); list.replaceChildren();
  if (result.status === 'NO_EVIDENCE' || !result.citations?.length) {
    list.append(emptyState(result.errorCode ? `No evidence · ${result.errorCode}` : 'No evidence found.'));
    return;
  }
  result.citations.forEach((citation) => {
    const item = document.createElement('article'); item.className = 'citation-item';
    const heading = document.createElement('div'); heading.className = 'citation-heading';
    const title = document.createElement('strong'); title.textContent = citation.title;
    const score = document.createElement('span'); score.textContent = `BM25 ${Number(citation.score).toFixed(2)}`;
    heading.append(title, score);
    const source = document.createElement('span'); source.className = 'citation-source';
    source.textContent = `${citation.externalKey} · v${citation.version} · ${citation.section || 'General'} · ${citation.uri}`;
    const excerpt = document.createElement('p'); excerpt.textContent = citation.excerpt;
    item.append(heading, source, excerpt); list.append(item);
  });
}

async function publishDocument(event) {
  event.preventDefault();
  const request = {
    externalKey: $('#document-key').value.trim(), title: $('#document-title').value.trim(),
    category: $('#document-category').value.trim(), section: $('#document-section').value.trim(),
    content: $('#document-content').value, regionScope: $('#document-regions').value.split(',').map((value) => value.trim()).filter(Boolean),
    effectiveAt: new Date().toISOString()
  };
  const document = await api('/api/v1/knowledge/documents', { method: 'POST', body: JSON.stringify(request) });
  showNotice(`Document ${document.externalKey} published as v${document.version}.`);
  $('#document-form').reset(); $('#document-category').value = 'SOP'; $('#document-section').value = 'Dispatch'; $('#document-regions').value = region();
  await loadKnowledge();
}

async function connect(event) {
  event.preventDefault();
  state.username = $('#username').value.trim(); state.password = $('#password').value;
  $('#connection-state').textContent = 'Checking access…';
  try {
    await loadDashboard();
    const system = await api('/api/v1/system/info');
    state.roles = system.authorities || [];
    $('#connection-state').textContent = `Connected · ${state.username}`;
    showNotice('Connected to operations API.');
  } catch (error) {
    $('#connection-state').textContent = error.message.includes('403') ? 'Access denied' : 'Connection failed';
    showNotice(error.message, true);
  }
}

function initializeDates() {
  const to = new Date();
  const from = new Date(to.getTime() - 24 * 60 * 60 * 1000);
  const local = (date) => new Date(date.getTime() - date.getTimezoneOffset() * 60000).toISOString().slice(0, 16);
  $('#from-date').value = local(from); $('#to-date').value = local(to);
}

function initialize() {
  state.agentSessionId = sessionStorage.getItem('peakdispatch-agent-session');
  initializeDates();
  $('#policy-json').value = JSON.stringify({ etaWeight: 0.65, loadWeight: 0.20, urgencyWeight: 0.15,
    maxPickupDistanceKm: 15, heartbeatTimeoutSeconds: 75, offerTtlSeconds: 30, maxRounds: 5,
    retryBackoffSeconds: [5, 15, 30, 60] }, null, 2);
  $$('.nav-link').forEach((button) => button.addEventListener('click', () => setView(button.dataset.view)));
  $('#auth-form').addEventListener('submit', connect);
  $('#refresh-dashboard').addEventListener('click', () => loadDashboard().catch((error) => showNotice(error.message, true)));
  $('#timeline-form').addEventListener('submit', (event) => loadTimeline(event).catch((error) => showNotice(error.message, true)));
  $('#refresh-policies').addEventListener('click', () => loadPolicies().catch((error) => showNotice(error.message, true)));
  $('#policy-rows').addEventListener('click', (event) => policyAction(event).catch((error) => showNotice(error.message, true)));
  $('#create-policy').addEventListener('click', () => createPolicy().catch((error) => showNotice(error.message, true)));
  $('#validate-policy-json').addEventListener('click', () => {
    try { policyPayload(); $('#policy-validation').textContent = 'JSON is valid.'; }
    catch (error) { $('#policy-validation').textContent = error.message; }
  });
  $('#rollback-form').addEventListener('submit', (event) => rollbackPolicy(event).catch((error) => showNotice(error.message, true)));
  $('#replay-form').addEventListener('submit', (event) => runReplay(event).catch((error) => showNotice(error.message, true)));
  $('#compare-form').addEventListener('submit', (event) => compareReplays(event).catch((error) => showNotice(error.message, true)));
  $('#refresh-documents').addEventListener('click', () => loadKnowledge().catch((error) => showNotice(error.message, true)));
  $('#document-list').addEventListener('click', (event) => deactivateDocument(event).catch((error) => showNotice(error.message, true)));
  $('#knowledge-search-form').addEventListener('submit', (event) => searchKnowledge(event).catch((error) => showNotice(error.message, true)));
  $('#document-form').addEventListener('submit', (event) => publishDocument(event).catch((error) => showNotice(error.message, true)));
  $('#agent-chat-form').addEventListener('submit', runAgentChat);
  $('#new-agent-session').addEventListener('click', newAgentSession);
  $('#region-id').addEventListener('change', () => { $('#region-meta').textContent = region(); });
}

initialize();
