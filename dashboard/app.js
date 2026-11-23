"use strict";

const data = window.benchmarkData;
const element = (id) => document.getElementById(id);
const metrics = {
  rps: { name: "Throughput", unit: "Req/s", decimals: 0, direction: "Higher is better" },
  p50: { name: "P50 latency", unit: "ms", decimals: 3, direction: "Lower is better" },
  p95: { name: "P95 latency", unit: "ms", decimals: 3, direction: "Lower is better" },
  p99: { name: "P99 latency", unit: "ms", decimals: 3, direction: "Lower is better" },
  cpu: { name: "CPU cost", unit: "s / 1M req.", decimals: 1, direction: "Lower is better" },
  rss: { name: "Peak RSS", unit: "MiB", decimals: 1, direction: "Lower is better" },
};
const variants = {
  "ktor-jvm": { label: "Ktor/JVM", detail: "CIO · HotSpot", color: "jvm" },
  "ktor-native": { label: "Ktor/Native", detail: "CIO · Kotlin/Native", color: "native" },
  "rust-axum": { label: "Rust/Axum", detail: "Axum · Tokio", color: "rust" },
  "kotlin-reactor": { label: "Kotlin Reactor", detail: "Kotlin/Native · epoll", color: "reactor" },
  "go-http": { label: "Go", detail: "net/http", color: "go" },
  "hono-node": { label: "Hono", detail: "Node.js · node-server", color: "hono" },
  "elysia-bun": { label: "Elysia", detail: "Bun", color: "elysia" },
  fastapi: { label: "FastAPI", detail: "CPython · Uvicorn", color: "python" },
  "spring-boot": { label: "Spring Boot", detail: "Java · MVC / Tomcat", color: "spring" },
};
const dateFormat = new Intl.DateTimeFormat("en-GB", {
  dateStyle: "medium", timeStyle: "short",
});
const utcDateFormat = new Intl.DateTimeFormat("en-GB", {
  dateStyle: "medium", timeStyle: "short", timeZone: "UTC",
});
const shortDate = new Intl.DateTimeFormat("en-GB", { month: "short", day: "numeric", timeZone: "UTC" });
const shortTime = new Intl.DateTimeFormat("en-GB", { hour: "2-digit", minute: "2-digit", timeZone: "UTC" });
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
  const reactor = groups.find((group) => group.variant === "kotlin-reactor");
  const ordered = reactor ? [reactor, ...groups.filter((group) => group !== reactor)] : groups;
  const rows = ordered.map((group) => {
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

function drawHistory(canvas, points, metric) {
  if (!points.length || !canvas.clientWidth) return;
  const ctx = canvas.getContext("2d");
  const width = canvas.clientWidth;
  const height = canvas.clientHeight;
  const pixelRatio = window.devicePixelRatio || 1;
  canvas.width = Math.round(width * pixelRatio);
  canvas.height = Math.round(height * pixelRatio);
  ctx.scale(pixelRatio, pixelRatio);
  const accent = getComputedStyle(canvas).getPropertyValue("--reactor-accent").trim();
  ctx.font = "12px system-ui, sans-serif";
  const max = Math.max(...points.map((point) => point.values.max));
  const min = Math.min(...points.map((point) => point.values.min));
  const padding = Math.max((max - min) * 0.12, max * 0.025, 0.001);
  const upper = max + padding;
  const lower = Math.max(0, min - padding);
  const ticks = Array.from({ length: 5 }, (_, index) => lower + (upper - lower) * index / 4);
  const left = Math.max(...ticks.map((value) => ctx.measureText(format(value, metric)).width)) + 14;
  const right = width - 12;
  const top = 12;
  const bottom = height - 24;
  const y = (value) => bottom - (value - lower) / (upper - lower) * (bottom - top);
  const first = Date.parse(points[0].run.date);
  const last = Date.parse(points.at(-1).run.date);
  const x = (point) => first === last ? (left + right) / 2
    : left + (Date.parse(point.run.date) - first) / (last - first) * (right - left);

  ctx.fillStyle = "#50617a";
  ctx.strokeStyle = "#dce5f0";
  ctx.textAlign = "right";
  ctx.textBaseline = "middle";
  for (const value of ticks) {
    const line = y(value);
    ctx.fillText(format(value, metric), left - 8, line);
    ctx.beginPath();
    ctx.moveTo(left, line);
    ctx.lineTo(right, line);
    ctx.stroke();
  }
  ctx.textBaseline = "bottom";
  const axisDate = first !== last && shortDate.format(first) === shortDate.format(last) ? shortTime : shortDate;
  ctx.textAlign = first === last ? "center" : "left";
  ctx.fillText(axisDate.format(first), x(points[0]), height);
  if (last !== first && width > 180) {
    ctx.textAlign = "right";
    ctx.fillText(axisDate.format(last), right, height);
  }

  ctx.strokeStyle = "#d7a18b";
  for (const point of points) {
    const center = x(point);
    const low = y(point.values.min);
    const high = y(point.values.max);
    ctx.beginPath();
    ctx.moveTo(center, low);
    ctx.lineTo(center, high);
    ctx.moveTo(center - 4, low);
    ctx.lineTo(center + 4, low);
    ctx.moveTo(center - 4, high);
    ctx.lineTo(center + 4, high);
    ctx.stroke();
  }
  ctx.strokeStyle = accent;
  ctx.lineWidth = 2.5;
  ctx.beginPath();
  points.forEach((point, index) => {
    if (index === 0) ctx.moveTo(x(point), y(point.values.median));
    else ctx.lineTo(x(point), y(point.values.median));
  });
  ctx.stroke();
  ctx.fillStyle = accent;
  for (const point of points) {
    ctx.beginPath();
    ctx.arc(x(point), y(point.values.median), 4, 0, 2 * Math.PI);
    ctx.fill();
  }
}

function startHistory(runs) {
  const profiles = data.history ?? [];
  if (!profiles.length) {
    element("history-empty").hidden = false;
    return;
  }
  const byId = new Map(runs.map((run) => [run.id, run]));
  const profileSelect = element("history-profile");
  const endpointSelect = element("history-endpoint");
  const metricSelect = element("history-metric");
  const canvas = element("history-chart");
  let points = [];

  profileSelect.replaceChildren(...profiles.map((profile, index) => new Option(
    [profile.label ?? profile.machine, profile.host, profile.parameters.connections + " connections"].join(" · "),
    String(index),
  )));
  metricSelect.replaceChildren(...Object.entries(metrics).map(([key, info]) => new Option(info.name, key)));

  function update() {
    const profile = profiles[Number(profileSelect.value)];
    const metric = metricSelect.value;
    const info = metrics[metric];
    const { connections, threads, warmup_s, target_duration_s } = profile.parameters;
    element("history-workload").textContent =
      [profile.os, profile.host, connections + " connections", threads + " wrk threads",
       warmup_s + " s warm-up", target_duration_s + " s trial"].join(" · ");
    points = profile.runs.filter((entry) => entry.endpoints.includes(endpointSelect.value)).map((entry) => {
      const run = byId.get(entry.id);
      const group = run.groups.find((item) => item.variant === "kotlin-reactor" && item.endpoint === endpointSelect.value);
      return { run, group, values: group.stats[metric] };
    });
    element("history-empty").hidden = Boolean(points.length);
    element("history-data").hidden = !points.length;
    if (!points.length) return;

    const latest = points.at(-1).values;
    const previous = points.at(-2)?.values;
    const delta = previous?.median > 0 ? (latest.median / previous.median - 1) * 100 : null;
    element("history-value").textContent = format(latest.median, metric) + " " + info.unit;
    element("history-metric-name").textContent = info.name + " · latest median";
    element("history-direction").textContent = info.direction;
    const deltaNode = element("history-delta");
    deltaNode.textContent = delta === null ? "First comparable point"
      : new Intl.NumberFormat("en-GB", { maximumFractionDigits: 1, signDisplay: "exceptZero" }).format(delta)
        + "% vs previous run";
    const rangesOverlap = previous && latest.min <= previous.max && previous.min <= latest.max;
    deltaNode.dataset.change = !delta || rangesOverlap ? "neutral"
      : (metric === "rps" ? delta > 0 : delta < 0) ? "better" : "worse";
    if (rangesOverlap) deltaNode.textContent += " · ranges overlap";
    element("history-caveat").textContent = points.some((point) => point.run.ci_run_id)
      ? "Exploratory hosted CI. Differences can reflect runner load as well as code changes."
      : "Runs use the same recorded hardware class and workload.";

    const rows = points.slice().reverse().map(({ run, group, values }) => {
      const row = make("tr");
      const heading = make("th", utcDateFormat.format(new Date(run.date)));
      heading.scope = "row";
      const sources = make("td");
      const links = make("div", "", "history-sources");
      if (/^[0-9a-f]{40}$/.test(run.sha ?? "")) {
        const commit = make("a", run.sha.slice(0, 7));
        commit.href = data.repository + "/commit/" + run.sha;
        commit.setAttribute("aria-label", "Measured commit " + run.sha.slice(0, 7));
        links.append(commit);
      }
      links.append(fileLink(run, "Raw logs", run.folder, true));
      sources.append(links);
      row.append(heading, make("td", run.feature ?? run.sha?.slice(0, 7) ?? "—"),
        make("td", format(values.median, metric) + " " + info.unit),
        make("td", [values.min, values.max].map((value) => format(value, metric)).join("–")),
        make("td", group.valid + " / " + group.total), sources);
      return row;
    });
    element("history-rows").replaceChildren(...rows);
    drawHistory(canvas, points, metric);
  }

  function changeProfile() {
    const previous = endpointSelect.value;
    const endpoints = [...new Set(profiles[Number(profileSelect.value)].runs.flatMap((entry) => entry.endpoints))];
    endpointSelect.replaceChildren(...endpoints.map((endpoint) => new Option(endpoint)));
    if (endpoints.includes(previous)) endpointSelect.value = previous;
    update();
  }

  profileSelect.addEventListener("change", changeProfile);
  endpointSelect.addEventListener("change", update);
  metricSelect.addEventListener("change", update);
  new ResizeObserver(() => drawHistory(canvas, points, metricSelect.value)).observe(canvas);
  element("history-controls").hidden = false;
  changeProfile();
}

function start() {
  const runs = data?.runs ?? [];
  if (!runs.length) {
    element("empty").hidden = false;
    element("details-link").hidden = true;
    element("history-link").hidden = true;
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
  startHistory(runs);
}

start();
