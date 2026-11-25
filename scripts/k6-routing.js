import execution from "k6/execution";
import http from "k6/http";
import { Counter, Trend } from "k6/metrics";

const base = __ENV.ROUTING_URL;
const resources = Number(__ENV.ROUTING_RESOURCES);
const rate = Number(__ENV.ROUTING_RATE);
const seconds = Number(__ENV.ROUTING_SECONDS);
const vus = Number(__ENV.ROUTING_VUS);
const failures = new Counter("routing_failures");
const latency = new Trend("routing_latency", true);

export const options = {
  scenarios: {
    routes: {
      executor: "constant-arrival-rate", rate, duration: `${seconds}s`, preAllocatedVUs: vus, gracefulStop: "2s",
    },
  },
  maxRedirects: 0,
  summaryTrendStats: ["p(50)", "p(95)", "p(99)", "count"],
};

export default function () {
  const iteration = execution.scenario.iterationInTest % 4096;
  const resource = (iteration * 137 + 29) % resources;
  const id = iteration % 8 === 0 ? 0 : iteration + 1;
  const path = "/api/v1/r" + resource + "/items/" + (id === 0 ? "current" : id);
  const expected = '{"resource":' + resource + ',"id":' + id + '}';
  const result = http.get(base + path, { timeout: "2s", tags: { name: "routes" } });
  const valid = result.status === 200 && result.proto === "HTTP/1.1" && result.body === expected;
  failures.add(valid ? 0 : 1);
  if (valid) latency.add(result.timings.duration);
}

export function handleSummary(data) {
  return { [__ENV.ROUTING_OUTPUT]: JSON.stringify(data) };
}
