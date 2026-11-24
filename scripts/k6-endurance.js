import execution from "k6/execution";
import { Counter, Gauge } from "k6/metrics";
import runMix, { options as mixOptions } from "./k6-integration.js";

const phases = JSON.parse(__ENV.PERFLAB_ENDURANCE_PHASES);
const seconds = Number(__ENV.PERFLAB_ENDURANCE_WINDOW_SECONDS);
const baseRate = Number(__ENV.PERFLAB_RATE);
const windowCount = phases.reduce((total, phase) => total + phase.seconds / seconds, 0);
const starts = new Counter("perflab_endurance_starts");
const scenarioStart = new Gauge("perflab_endurance_start_epoch_ms");
const thresholds = {};

// These thresholds retain tagged submetrics. Trial errors are reported as measurements.
for (let window = 0; window < windowCount; window++) {
  const tag = `{endurance_window:${window}}`;
  thresholds[`perflab_endurance_starts${tag}`] = ["count>=0"];
  thresholds[`perflab_success_duration${tag}`] = ["p(99)>=0"];
  thresholds[`perflab_failed_responses${tag}`] = ["count>=0"];
}

export const options = {
  ...mixOptions,
  thresholds,
  scenarios: Object.fromEntries(Object.entries(mixOptions.scenarios).map(([name, config]) => [
    name, {
      executor: "ramping-arrival-rate",
      preAllocatedVUs: config.preAllocatedVUs,
      gracefulStop: config.gracefulStop,
      startRate: config.rate,
      stages: phases.flatMap((phase) => {
        const target = phase.rate * config.rate / baseRate;
        return [{duration: "0s", target}, {duration: `${phase.seconds}s`, target}];
      }),
    },
  ])),
};

export default function () {
  const { scenario } = execution;
  if (scenario.name === "json" && scenario.iterationInTest === 0) scenarioStart.add(scenario.startTime);
  const elapsed = Date.now() - scenario.startTime;
  const window = Math.min(windowCount - 1, Math.max(0, Math.floor(elapsed / (seconds * 1000))));
  execution.vu.metrics.tags.endurance_window = String(window);
  starts.add(1);
  runMix();
}

export function handleSummary(data) {
  return { [__ENV.PERFLAB_SUMMARY]: JSON.stringify(data) };
}
