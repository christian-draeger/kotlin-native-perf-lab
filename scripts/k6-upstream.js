import http from "k6/http";
import exec from "k6/execution";
import { Counter, Trend } from "k6/metrics";

const cases = {
  fast: { weight: 70, status: 200, body: "origin-ready" },
  slow: { weight: 20, status: 200, body: "origin-ready" },
  deadline: { weight: 5, status: 504, body: "origin-deadline" },
  disconnect: { weight: 5, status: 502, body: "origin-unavailable" },
};
const rate = Number(__ENV.UPSTREAM_RATE);
const duration = Number(__ENV.UPSTREAM_DURATION);
if (!(rate > 0 && Number.isInteger(rate / 20) && duration > 0)) {
  throw new Error("Use positive duration and a rate divisible by 20.");
}

const durations = {};
const scenarios = {};
for (const [name, config] of Object.entries(cases)) {
  durations[name] = new Trend(`bench_${name}_duration`, true);
  scenarios[name] = {
    executor: "constant-arrival-rate",
    duration: `${duration}s`,
    rate: config.weight * rate / 100,
    preAllocatedVUs: 16,
    gracefulStop: "2s",
  };
}
const mismatches = new Counter("bench_response_mismatches");

export const options = {
  scenarios,
  maxRedirects: 0,
  summaryTrendStats: ["p(50)", "p(95)", "p(99)", "count"],
};

export default function () {
  const name = exec.scenario.name;
  const config = cases[name];
  const response = http.get(`${__ENV.UPSTREAM_FRONTEND}/upstream/${name}`, {
    timeout: "1s",
    responseCallback: http.expectedStatuses(config.status),
  });
  if (response.status === config.status && response.proto === "HTTP/1.1" && response.body === config.body
      && /^text\/plain(?:;|$)/.test(response.headers["Content-Type"] || "")) {
    durations[name].add(response.timings.duration);
  } else {
    mismatches.add(1);
  }
}

export function handleSummary(summary) {
  return { [__ENV.UPSTREAM_SUMMARY]: JSON.stringify(summary) };
}
