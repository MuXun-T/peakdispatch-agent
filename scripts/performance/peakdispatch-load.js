import http from 'k6/http';
import { SharedArray } from 'k6/data';
import { check } from 'k6';
import encoding from 'k6/encoding';
import execution from 'k6/execution';
import { Counter, Rate, Trend } from 'k6/metrics';

const scenario = __ENV.PD_PERF_SCENARIO || 'steady';
const parsedUsers = JSON.parse(__ENV.PD_PERF_USERS || '[]');
const users = Array.isArray(parsedUsers) ? parsedUsers : parsedUsers ? [parsedUsers] : [];
const scaled = scenario === 'scale-uniform' || scenario === 'scale-hotspot';
const couriers = scaled && __ENV.PD_PERF_COURIERS_FILE
  ? new SharedArray('couriers', () => JSON.parse(open(__ENV.PD_PERF_COURIERS_FILE)))
  : [];
const baseUrl = (__ENV.PD_PERF_BASE_URL || 'http://localhost:8080').replace(/\/$/, '');
const targetRate = scenario === 'steady' ? 100 : 200;
const duration = scenario === 'steady' ? '10m' : scenario === 'burst' ? '1m' : '100s';
const heartbeatSequenceOffset = scenario === 'scale-hotspot' ? 1000000 : 0;
const preAllocatedVUs = Number(__ENV.PD_PERF_PREALLOCATED_VUS || 200);
const maxVUs = Number(__ENV.PD_PERF_MAX_VUS || 1000);
const ordersAccepted = new Counter('peakdispatch_orders_accepted');
const heartbeatsAccepted = new Counter('peakdispatch_heartbeats_accepted');
const failed = new Rate('peakdispatch_load_error_rate');
const orderRequestLatency = new Trend('peakdispatch_order_request_ms', true);
const scenarios = {
  orders: {
    executor: 'constant-arrival-rate',
    rate: targetRate,
    timeUnit: '1s',
    duration,
    preAllocatedVUs,
    maxVUs,
    exec: 'createOrder',
  },
};

if (scaled) {
  scenarios.courierHeartbeats = {
    executor: 'constant-arrival-rate',
    rate: 167,
    timeUnit: '1s',
    duration: '100s',
    preAllocatedVUs,
    maxVUs,
    exec: 'sendHeartbeat',
  };
}

export const options = {
  scenarios,
  thresholds: {
    peakdispatch_load_error_rate: ['rate<0.01'],
  },
  summaryTrendStats: ['count', 'avg', 'min', 'med', 'max', 'p(50)', 'p(95)', 'p(99)'],
};

function authHeaders(identity, idempotencyKey) {
  return {
    Authorization: 'Basic ' + encoding.b64encode(identity.username + ':' + identity.password),
    'Content-Type': 'application/json',
    'Idempotency-Key': idempotencyKey,
  };
}

function locationFor(iteration) {
  if (scenario === 'scale-hotspot' && iteration % 5 !== 0) {
    return { lat: 30.25, lon: 120.16 };
  }
  const gridIndex = scenario === 'scale-hotspot'
    ? Math.floor(iteration / 5) % 10000
    : iteration % 10000;
  return {
    lat: 30.2 + Math.floor(gridIndex / 100) * 0.001,
    lon: 120.1 + (gridIndex % 100) * 0.001,
  };
}

export function createOrder() {
  if (users.length === 0) {
    throw new Error('PD_PERF_USERS must contain authorized simulation merchant credentials');
  }
  const iteration = execution.scenario.iterationInTest || 0;
  const identity = users[iteration % users.length];
  const pickup = scaled ? locationFor(iteration) : { lat: 30.25, lon: 120.16 };
  const now = new Date();
  const body = JSON.stringify({
    merchantId: identity.merchantId,
    regionId: identity.regionId,
    pickup,
    dropoff: { lat: pickup.lat + 0.002, lon: pickup.lon + 0.002 },
    prepEta: new Date(now.getTime() + 15 * 60 * 1000).toISOString(),
    deadline: new Date(now.getTime() + 60 * 60 * 1000).toISOString(),
  });
  const started = Date.now();
  const response = http.post(baseUrl + '/api/v1/orders', body, {
    headers: authHeaders(identity, 'perf-order-' + scenario + '-' + iteration),
    tags: { endpoint: 'create-order', workload: scenario },
    timeout: '10s',
  });
  orderRequestLatency.add(Date.now() - started);
  const ok = check(response, { 'order accepted with 201': (result) => result.status === 201 });
  failed.add(!ok);
  if (ok) ordersAccepted.add(1);
}

export function sendHeartbeat() {
  if (couriers.length !== 5000) {
    throw new Error('PD_PERF_COURIERS_FILE must contain exactly 5,000 online simulation couriers');
  }
  const iteration = execution.scenario.iterationInTest || 0;
  const courier = couriers[iteration % couriers.length];
  const sequence = Number(courier.nextSequence) + heartbeatSequenceOffset
    + Math.floor(iteration / couriers.length);
  const location = locationFor(iteration);
  const body = JSON.stringify({
    regionId: courier.regionId,
    presenceGeneration: Number(courier.presenceGeneration),
    sequence,
    lat: Number(courier.lat ?? location.lat),
    lon: Number(courier.lon ?? location.lon),
    occurredAt: new Date().toISOString(),
  });
  const response = http.post(baseUrl + '/api/v1/couriers/' + courier.courierId + '/heartbeat', body, {
    headers: authHeaders(courier, 'perf-heartbeat-' + courier.courierId + '-' + sequence),
    tags: { endpoint: 'courier-heartbeat', workload: scenario },
    timeout: '10s',
  });
  const ok = check(response, { 'heartbeat accepted with 200': (result) => result.status === 200 });
  failed.add(!ok);
  if (ok) heartbeatsAccepted.add(1);
}
