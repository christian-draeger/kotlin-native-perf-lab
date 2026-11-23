"use strict";

const data = window.benchmarkData;
const element = (id) => document.getElementById(id);
const metrics = {
  rps: { name: "Throughput", unit: "Req/s", decimals: 0, direction: "Higher is better" },
  p50: { unit: "ms", decimals: 3 },
  p95: { unit: "ms", decimals: 3 },
  p99: { name: "P99 latency", unit: "ms", decimals: 3, direction: "Lower is better" },
  cpu: { name: "CPU cost", unit: "s / 1M req.", decimals: 1, direction: "Lower is better" },
  rss: { name: "Peak RSS", unit: "MiB", decimals: 1, direction: "Lower is better" },
};
const variants = {
  "ktor-jvm": { label: "Ktor/JVM", detail: "CIO · HotSpot", color: "jvm" },
  "ktor-native": { label: "Ktor/Native", detail: "CIO · Kotlin/Native", color: "native" },
  "rust-axum": { label: "Rust/Axum", detail: "Axum · Tokio", color: "rust" },
  "kotlin-reactor": { label: "Kotlin Reactor", detail: "Kotlin/Native · epoll", color: "reactor" },
};
const dateFormat = new Intl.DateTimeFormat("en-GB", {
  dateStyle: "medium", timeStyle: "short",
});
const format = (value, key) => new Intl.NumberFormat("en-GB", {
  maximumFractionDigits: metrics[key].decimals,
}).format(value);

function make(tag, text = "", className = "") {
  const node = document.createElement(tag);
  node.textContent = text;
  node.className = className;
  return node;
}

function variantInfo(name) {
  return variants[name] ?? { label: name, detail: "", color: "other" };
}

function fileLink(run, label, path, directory = false) {
  const link = make("a", label);
  const kind = directory ? "tree" : "blob";
  link.href = [data.repository, kind, run.revision, path].join("/");
  return link;
}

function renderChart(groups, metric) {
  const info = metrics[metric];
  const scale = Math.max(1, ...groups.map((group) => group.stats?.[metric].max ?? 0)) * 1.06;
  element("metric-name").textContent = info.name;
  element("metric-direction").textContent = info.direction;
  const rows = groups.map((group) => {
    const variant = variantInfo(group.variant);
    const stats = group.stats?.[metric];
    const row = make("div", "", "chart-row " + variant.color);
    const heading = make("div", "", "chart-row-heading");
    const name = make("div", "", "variant-name");
    name.append(make("i", "", "swatch"), make("strong", variant.label), make("span", variant.detail));
    const reading = make("div", "", "chart-value");
    if (stats) {
      reading.append(make("strong", format(stats.median, metric)), make("span", info.unit));
    } else {
      reading.append(make("span", "No valid trials"));
    }
    heading.append(name, reading);
    row.append(heading);
    if (stats) {
      const track = make("div", "", "track");
      track.setAttribute("aria-hidden", "true");
      const bar = make("div", "", "median");
      bar.style.width = stats.median / scale * 100 + "%";
      const range = make("div", "", "range");
      range.style.left = stats.min / scale * 100 + "%";
      range.style.width = (stats.max - stats.min) / scale * 100 + "%";
      track.append(bar, range);
      row.append(track, make("p",
        [format(stats.min, metric), format(stats.max, metric)].join("–") + " " + info.unit,
        "run-range"));
    }
    return row;
  });
  element("chart").replaceChildren(...rows);
}

function renderTable(groups, endpoint) {
  element("table-endpoint").textContent = endpoint;
  const rows = groups.map((group) => {
    const variant = variantInfo(group.variant);
    const row = make("tr", "", variant.color);
    const heading = make("th");
    heading.scope = "row";
    heading.append(make("i", "", "swatch"), make("span", variant.label));
    row.append(heading, make("td", group.valid + " / " + group.total));
    for (const metric of ["rps", "p50", "p95", "p99", "cpu", "rss"]) {
      row.append(make("td", group.stats ? format(group.stats[metric].median, metric) : "—"));
    }
    return row;
  });
  element("rows").replaceChildren(...rows);
}

function renderMethod(run) {
  element("host").textContent = [run.note.host, run.os, run.machine].filter(Boolean).join(" · ");
  const parameters = [
    ["Connections", "connections", ""],
    ["wrk threads", "threads", ""],
    ["Warm-up", "warmup_s", " s"],
    ["Target duration", "target_duration_s", " s"],
  ].flatMap(([label, key, unit]) => {
    const values = run.parameters[key];
    return [make("dt", label), make("dd", values.length ? values.join(" / ") + unit : "—")];
  });
  element("parameters").replaceChildren(...parameters);
  const caveat = run.note.note ?? (run.local ? "wrk and the servers shared one host." : "");
  element("caveat").textContent = caveat;
  element("caveat").hidden = !caveat;
  const sources = [
    fileLink(run, "CSV", run.folder + "/results.csv"),
    fileLink(run, "Raw logs", run.folder, true),
  ];
  if (run.has_environment) {
    sources.push(fileLink(run, "Environment", run.folder + "/environment.txt"));
  }
  sources.push(...(run.note.links ?? []).map((link) => fileLink(run, link.label, link.path)));
  if (/^[0-9]+$/.test(run.ci_run_id ?? "")) {
    const action = make("a", "CI workflow");
    action.href = data.repository + "/actions/runs/" + run.ci_run_id;
    sources.push(action);
  }
  if (/^[0-9a-f]{40}$/.test(run.sha ?? "")) {
    const commit = make("a", run.sha.slice(0, 7));
    commit.href = data.repository + "/commit/" + run.sha;
    commit.setAttribute("aria-label", "Measured commit " + run.sha.slice(0, 7));
    sources.push(commit);
  }
  element("source-links").replaceChildren(...sources);
}

function start() {
  const runs = data?.runs ?? [];
  if (!runs.length) {
    element("empty").hidden = false;
    element("details-link").hidden = true;
    return;
  }

  const runSelect = element("run-select");
  const endpointSelect = element("endpoint");
  const buttons = [...element("metrics").querySelectorAll("button")];
  let metric = "rps";
  let run = runs[0];

  element("repository").href = data.repository;
  runSelect.replaceChildren(...runs.map((entry) =>
    new Option([entry.note.label, dateFormat.format(new Date(entry.date))].filter(Boolean).join(" · "), entry.id)
  ));

  function updateComparison() {
    const endpoint = endpointSelect.value;
    const groups = run.groups.filter((group) => group.endpoint === endpoint);
    renderChart(groups, metric);
    renderTable(groups, endpoint);
  }

  function updateRun() {
    run = runs.find((entry) => entry.id === runSelect.value) ?? runs[0];
    element("run-kind").textContent = run.note.kind ?? (run.local ? "Local benchmark" : "Benchmark");
    element("run-context").textContent = [
      run.ci_run_id ? "GitHub-hosted Linux" : "",
      run.local ? "Same-host load generator" : "",
    ].filter(Boolean).join(" · ");
    element("trial-count").textContent = run.valid + " / " + run.total + " valid trials" + (run.incomplete ? " · incomplete run" : "");
    element("trial-count").classList.toggle("incomplete", run.incomplete || run.valid < run.total);
    const previous = endpointSelect.value;
    const endpoints = [...new Set(run.groups.map((group) => group.endpoint))];
    endpointSelect.replaceChildren(...endpoints.map((endpoint) => new Option(endpoint)));
    if (endpoints.includes(previous)) endpointSelect.value = previous;
    renderMethod(run);
    updateComparison();
  }

  buttons.forEach((button) => button.addEventListener("click", () => {
    metric = button.dataset.metric;
    buttons.forEach((choice) => choice.setAttribute("aria-pressed", String(choice === button)));
    updateComparison();
  }));
  runSelect.addEventListener("change", updateRun);
  endpointSelect.addEventListener("change", updateComparison);
  updateRun();
  element("run-picker").hidden = false;
  element("dashboard").hidden = false;
}

start();
