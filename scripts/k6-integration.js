import execution from "k6/execution";
import http from "k6/http";
import { Counter, Trend } from "k6/metrics";

const target = __ENV.PERFLAB_TARGET;
const rate = Number(__ENV.PERFLAB_RATE);
const duration = Number(__ENV.PERFLAB_DURATION);
const vus = Number(__ENV.PERFLAB_VUS);
const summary = __ENV.PERFLAB_SUMMARY;
const standalone = __ENV.PERFLAB_APPLICATION_MIX === "true";
const profile = __ENV.PERFLAB_APPLICATION_PROFILE ?? "persistent";
const churn = profile === "churn";
const overload = profile === "overload";
const capacity = profile === "capacity";
const budgets = capacity ? JSON.parse(__ENV.PERFLAB_TRAFFIC_BUDGETS) : {};
if (!target || !summary || !Number.isInteger(rate) || rate < 20 || rate % 20 !== 0
    || !Number.isInteger(duration) || duration < 1 || !Number.isInteger(vus) || vus < 1) {
  throw new Error("Set PERFLAB_TARGET, PERFLAB_RATE (multiple of 20), PERFLAB_DURATION, PERFLAB_VUS and PERFLAB_SUMMARY.");
}

// One request per iteration preserves the configured HTTP request rate.
// A fixed VU budget makes dropped iterations visible without allocating VUs during measurement.
const operations = {
  json: { weight: standalone ? 6 : 8, path: "/json", type: "application/json", validate: user },
  catalog: { weight: 5, path: "/catalog", type: "application/json", validate: catalog },
  upload_1k: upload(2, 1024),
  upload_24k: upload(1, 24 * 1024),
  upload_64k: upload(2, 64 * 1024),
  download: { weight: 2, path: "/download", type: "application/octet-stream", validate: download },
};
if (standalone) {
  operations.wait = { weight: 2, path: "/wait", type: "text/plain", validate: (response) => response.body === "ready" };
}
const streamBody = "s".repeat(16 * 1024) + "t".repeat(16 * 1024 + 3);
const successfulLatency = new Trend("perflab_success_duration", true);
const failure = new Counter("perflab_failed_responses");
const newConnections = new Counter("perflab_new_connections");
const timeouts = new Counter("perflab_timeouts");
const onTime = new Counter("perflab_success_within_100ms");
const onTimePerOperation = capacity ? new Counter("perflab_success_within_operation_budget") : null;
const operationLatency = {};
const operationFailures = {};
const scenarios = {};
for (const [name, operation] of Object.entries(operations)) {
  operationLatency[name] = new Trend(`perflab_${name}_duration`, true);
  operationFailures[name] = new Counter(`perflab_${name}_failed`);
  scenarios[name] = {
    executor: "constant-arrival-rate", rate: rate / 20 * operation.weight,
    duration: `${duration}s`, preAllocatedVUs: vus, gracefulStop: "3s",
  };
}

export const options = {
  scenarios, maxRedirects: 0, noConnectionReuse: churn,
  summaryTrendStats: ["p(50)", "p(95)", "p(99)", "count"],
};

function upload(weight, size) {
  return {
    weight, path: "/upload", type: "text/plain", body: "x".repeat(size),
    validate: (response) => response.body === String(size),
  };
}

function user(response) {
  if (capacity) return response.body === '{"id":42,"name":"benchmark-user","active":true}';
  const data = response.json();
  return Object.keys(data).length === 3 && data.id === 42 && data.name === "benchmark-user" && data.active === true;
}

// Check every response while avoiding per-request JSON decoding in the capacity generator.
const expectedCatalog = capacity ? Object.fromEntries([123, 987].map((id) => [
  id, JSON.stringify({ id, items: Array.from({ length: 32 }, (_, index) => ({
    sku: `${id}-${index}`, name: `Café item ${index}`,
    description: "Roasted in München and packed for delivery.",
    stock: { available: index > 0, units: index * 3 },
  })) }),
])) : {};

function catalog(response, id) {
  if (capacity) return response.body === expectedCatalog[id];
  const data = response.json();
  return Object.keys(data).length === 2 && data.id === id && Array.isArray(data.items)
    && data.items.length === 32 && data.items.every((item, index) => (
      Object.keys(item).length === 4 && item.sku === `${id}-${index}` && item.name === `Café item ${index}`
      && item.description === "Roasted in München and packed for delivery."
      && Object.keys(item.stock).length === 2 && item.stock.units === index * 3 && item.stock.available === (index > 0)
    ));
}

function download(response) {
  return response.body === streamBody;
}

export default function () {
  const name = execution.scenario.name;
  const operation = operations[name];
  const id = execution.scenario.iterationInTest % 2 === 0 ? 123 : 987;
  const path = name === "catalog" ? `${operation.path}/${id}` : operation.path;
  const params = { timeout: "2s", tags: { name } };
  const response = operation.body === undefined
    ? http.get(`${target}${path}`, params)
    : http.post(`${target}${path}`, operation.body, { ...params, headers: { "Content-Type": "application/octet-stream" } });
  let valid = false;
  try {
    valid = response.status === 200 && response.proto === "HTTP/1.1"
      && response.headers["Content-Type"]?.split(";", 1)[0] === operation.type && operation.validate(response, id);
  } catch (_) {
    // A malformed response is counted as a failure, including invalid JSON.
  }
  failure.add(valid ? 0 : 1);
  operationFailures[name].add(valid ? 0 : 1);
  if (churn || capacity) newConnections.add(response.timings.connecting > 0 ? 1 : 0);
  if (overload || capacity) timeouts.add(response.error_code === 1050 || response.error_code === 1211 ? 1 : 0);
  if (valid) {
    const duration = response.timings.duration + (churn ? response.timings.connecting : 0);
    successfulLatency.add(duration);
    operationLatency[name].add(duration);
    if (overload) onTime.add(duration <= 100 ? 1 : 0);
    if (capacity) onTimePerOperation.add(duration <= budgets[name] ? 1 : 0);
  }
}

export function handleSummary(data) {
  const rates = Object.fromEntries(Object.entries(scenarios).map(([name, config]) => [name, config.rate]));
  return { [summary]: JSON.stringify({ ...data, perflab_rates: rates }) };
}
