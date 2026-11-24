import http from "k6/http";
import { Counter, Trend } from "k6/metrics";

const failure = new Counter("perflab_failed_responses");
const successfulLatency = new Trend("perflab_success_duration", true);
const expected = '{"id":42,"name":"benchmark-user","active":true}';

export const options = {
  scenarios: {
    json: {
      executor: "constant-vus",
      vus: Number(__ENV.PERFLAB_VUS),
      duration: "5s",
      gracefulStop: "3s",
    },
  },
  maxRedirects: 0,
  summaryTrendStats: ["p(50)", "p(95)", "p(99)", "count"],
};

export default function () {
  const response = http.get(__ENV.PERFLAB_TARGET, { timeout: "2s" });
  const valid = response.status === 200 && response.proto === "HTTP/1.1"
    && response.headers["Content-Type"]?.split(";", 1)[0] === "application/json"
    && response.body === expected;
  failure.add(valid ? 0 : 1);
  if (valid) successfulLatency.add(response.timings.duration);
}

export function handleSummary(data) {
  return { [__ENV.PERFLAB_SUMMARY]: JSON.stringify(data) };
}
