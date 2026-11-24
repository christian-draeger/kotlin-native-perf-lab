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
  "ktor-jvm": { label: "Ktor/JVM", detail: "CIO · HotSpot", color: "kotlin" },
  "ktor-native": { label: "Ktor/Native", detail: "CIO · Kotlin/Native", color: "kotlin" },
  "rust-axum": { label: "Rust/Axum", detail: "Axum · Tokio", color: "rust" },
  "kotlin-reactor": { label: "NativeServe", detail: "Kotlin/Native · HTTP server", color: "reactor" },
  "go-http": { label: "Go", detail: "net/http", color: "go" },
  "hono-node": { label: "Hono", detail: "Node.js · node-server", color: "hono" },
  "elysia-bun": { label: "Elysia", detail: "Bun", color: "elysia" },
  fastapi: { label: "FastAPI", detail: "CPython · Uvicorn", color: "python" },
  flask: { label: "Flask", detail: "CPython · Waitress / WSGI", color: "python" },
  django: { label: "Django", detail: "CPython · Uvicorn / ASGI", color: "python" },
  "spring-boot": { label: "Spring Boot MVC", detail: "Java · Tomcat", color: "kotlin" },
  "spring-webflux": { label: "Spring Boot WebFlux", detail: "Java · Reactor Netty", color: "kotlin" },
  "aspnet-core": { label: "ASP.NET Core", detail: ".NET · Kestrel", color: "dotnet" },
  quarkus: { label: "Quarkus", detail: "Kotlin · Quarkus REST", color: "kotlin" },
  vertx: { label: "Vert.x", detail: "Kotlin · Vert.x Web", color: "kotlin" },
  micronaut: { label: "Micronaut", detail: "Kotlin · Netty", color: "kotlin" },
  http4k: { label: "http4k", detail: "Kotlin · Jetty", color: "kotlin" },
  javalin: { label: "Javalin", detail: "Kotlin · Jetty", color: "kotlin" },
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

function sortComparison(groups, metric) {
  const direction = metric === "rps" ? -1 : 1;
  return [...groups].sort((left, right) => {
    if (!left.stats) return right.stats ? 1 : 0;
    if (!right.stats) return -1;
    return direction * (left.stats[metric].median - right.stats[metric].median);
  });
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

function historyPlot(canvas, runs, bounds, metric) {
  if (!runs.length || !bounds.length || !canvas.clientWidth) return null;
  const ctx = canvas.getContext("2d");
  const width = canvas.clientWidth;
  const height = canvas.clientHeight;
  const pixelRatio = window.devicePixelRatio || 1;
  canvas.width = Math.round(width * pixelRatio);
  canvas.height = Math.round(height * pixelRatio);
  ctx.scale(pixelRatio, pixelRatio);
  ctx.font = "12px system-ui, sans-serif";
  const max = Math.max(...bounds.map((values) => values.max));
  const min = Math.min(...bounds.map((values) => values.min));
  const padding = Math.max((max - min) * 0.12, max * 0.025, 0.001);
  const upper = max + padding;
  const lower = Math.max(0, min - padding);
  const ticks = Array.from({ length: 5 }, (_, index) => lower + (upper - lower) * index / 4);
  const left = Math.max(...ticks.map((value) => ctx.measureText(format(value, metric)).width)) + 14;
  const right = width - 12;
  const top = 12;
  const bottom = height - 24;
  const y = (value) => bottom - (value - lower) / (upper - lower) * (bottom - top);
  const first = Date.parse(runs[0].date);
  const last = Date.parse(runs.at(-1).date);
  const x = (run) => first === last ? (left + right) / 2
    : left + (Date.parse(run.date) - first) / (last - first) * (right - left);

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
  const sameDay = first !== last && shortDate.format(first) === shortDate.format(last);
  const axisDate = (date) => sameDay ? shortTime.format(date)
    : last - first < 48 * 60 * 60 * 1000 ? shortDate.format(date) + " " + shortTime.format(date)
      : shortDate.format(date);
  ctx.textAlign = first === last ? "center" : "left";
  ctx.fillText(axisDate(first), x(runs[0]), height);
  if (last !== first && width >= 520) {
    ctx.textAlign = "center";
    ctx.fillText(axisDate((first + last) / 2), (left + right) / 2, height);
  }
  if (last !== first && width > 180) {
    ctx.textAlign = "right";
    ctx.fillText(axisDate(last), right, height);
  }

  return { ctx, x, y, top, bottom };
}

function drawHistory(canvas, points, metric) {
  const plot = historyPlot(canvas, points.map((point) => point.run), points.map((point) => point.values), metric);
  if (!plot) return;
  const { ctx, x, y } = plot;
  const accent = getComputedStyle(canvas).getPropertyValue("--reactor-accent").trim();
  ctx.strokeStyle = "#d7a18b";
  for (const point of points) {
    const center = x(point.run);
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
    if (index === 0) ctx.moveTo(x(point.run), y(point.values.median));
    else ctx.lineTo(x(point.run), y(point.values.median));
  });
  ctx.stroke();
  ctx.fillStyle = accent;
  for (const point of points) {
    ctx.beginPath();
    ctx.arc(x(point.run), y(point.values.median), 4, 0, 2 * Math.PI);
    ctx.fill();
  }
}

function profileLabel(profile) {
  return [profile.label ?? profile.machine, profile.host, profile.parameters.connections + " connections"].join(" · ");
}

function workloadLabel(profile) {
  const { connections, threads, warmup_s, target_duration_s } = profile.parameters;
  return [profile.os, profile.host, connections + " connections", threads + " wrk threads",
    warmup_s + " s warm-up", target_duration_s + " s trial"].join(" · ");
}

function metricOptions() {
  return Object.entries(metrics).map(([key, info]) => new Option(info.name, key));
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

  profileSelect.replaceChildren(...profiles.map((profile, index) => new Option(profileLabel(profile), index)));
  metricSelect.replaceChildren(...metricOptions());

  function update() {
    const profile = profiles[Number(profileSelect.value)];
    const metric = metricSelect.value;
    const info = metrics[metric];
    element("history-workload").textContent = workloadLabel(profile);
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

function drawFrameworkHistory(canvas, samples, series, metric, selectedRun, highlighted) {
  const bounds = samples.flatMap(({ groups }) => series.flatMap(({ name }) => {
    const values = groups.get(name)?.stats[metric];
    return values ? [{ min: values.median, max: values.median }] : [];
  }));
  const plot = historyPlot(canvas, samples.map(({ run }) => run), bounds, metric);
  if (!plot) return;
  const { ctx, x, y, top, bottom } = plot;
  if (selectedRun) {
    ctx.strokeStyle = "#a8b8cf";
    ctx.beginPath();
    ctx.moveTo(x(selectedRun), top);
    ctx.lineTo(x(selectedRun), bottom);
    ctx.stroke();
  }
  for (const { name, color, dash } of series) {
    const own = name === "kotlin-reactor";
    ctx.globalAlpha = highlighted && highlighted !== name ? 0.18 : 1;
    ctx.strokeStyle = color;
    ctx.fillStyle = color;
    ctx.lineWidth = own ? 3 : 1.65;
    ctx.setLineDash(dash);
    ctx.beginPath();
    const markers = [];
    for (const { run, groups } of samples) {
      const values = groups.get(name)?.stats[metric];
      if (!values) continue;
      const point = [x(run), y(values.median)];
      if (markers.length) ctx.lineTo(...point);
      else ctx.moveTo(...point);
      markers.push(point);
    }
    ctx.stroke();
    for (const [left, upper] of markers) {
      ctx.beginPath();
      ctx.arc(left, upper, own ? 3.5 : 2.5, 0, 2 * Math.PI);
      ctx.fill();
    }
  }
  ctx.globalAlpha = 1;
  ctx.setLineDash([]);
}

function startFrameworkHistory(runs) {
  const byId = new Map(runs.map((run) => [run.id, run]));
  const profiles = (data.framework_history ?? []).map((profile) => {
    const endpoints = new Map();
    for (const entry of profile.runs) {
      const run = byId.get(entry.id);
      for (const endpoint of entry.endpoints) {
        if (!endpoints.has(endpoint)) endpoints.set(endpoint, { samples: [], names: new Set() });
        const { samples, names } = endpoints.get(endpoint);
        const matched = run.groups.filter((group) => group.endpoint === endpoint && group.stats && group.valid === group.total);
        samples.push({ run, groups: new Map(matched.map((group) => [group.variant, group])) });
        matched.forEach(({ variant }) => names.add(variant));
      }
    }
    const orderedEndpoints = [...endpoints].sort(([left, a], [right, b]) =>
      b.names.size - a.names.size || Number(right === "/json") - Number(left === "/json"));
    return { ...profile, endpoints: new Map(orderedEndpoints), coverage: orderedEndpoints[0][1].names.size };
  }).sort((a, b) => b.coverage - a.coverage || b.runs.length - a.runs.length);
  element("framework-history-link").hidden = !profiles.length;
  if (!profiles.length) return;

  const profileSelect = element("framework-profile");
  const endpointSelect = element("framework-endpoint");
  const metricSelect = element("framework-metric");
  const runSelect = element("framework-run");
  const canvas = element("framework-chart");
  const details = element("framework-history").querySelector("details");
  const visibility = element("framework-visibility");
  const dashes = [[], [8, 4], [2, 4], [8, 3, 2, 3], [4, 3], [11, 4, 4, 4], [1, 3], [14, 5], [5, 3, 2, 3, 2, 3]];
  let samples, series;
  let highlighted;

  function draw() {
    const visible = series.filter(({ button }) => button.getAttribute("aria-pressed") === "true");
    canvas.hidden = !visible.length;
    element("framework-empty").hidden = Boolean(visible.length);
    visibility.textContent = visible.length === series.length ? "NativeServe only" : "Show all";
    const selectedRun = details.open ? byId.get(runSelect.value) : null;
    canvas.setAttribute("aria-label", metrics[metricSelect.value].name + " over time for " + visible.length + " frameworks");
    drawFrameworkHistory(canvas, samples, visible, metricSelect.value, selectedRun,
      visible.some(({ name }) => name === highlighted) ? highlighted : null);
  }

  function updateReadings() {
    const key = metricSelect.value;
    const { run, groups } = samples.find((sample) => sample.run.id === runSelect.value);
    element("framework-axis").textContent = metrics[key].name + " · " + metrics[key].unit;
    element("framework-direction").textContent = metrics[key].direction + " · UTC";
    element("framework-raw").href = fileLink(run, "Raw logs", run.folder, true).href;
    const rows = sortComparison([...groups.values()], key).map((group) => {
      const info = variantInfo(group.variant);
      const row = make("tr", "", info.color);
      const label = make("th");
      label.scope = "row";
      label.append(make("i", "", "swatch"), make("span", info.label));
      row.append(label, make("td", format(group.stats[key].median, key) + " " + metrics[key].unit),
        make("td", group.valid + " / " + group.total));
      return row;
    });
    element("framework-rows").replaceChildren(...rows);
    draw();
  }

  function changeEndpoint() {
    const profile = profiles[Number(profileSelect.value)];
    const endpoint = profile.endpoints.get(endpointSelect.value);
    samples = endpoint.samples;
    highlighted = null;
    const latestFirst = [...samples].reverse();
    runSelect.replaceChildren(...latestFirst.map(({ run, groups }) =>
      new Option(utcDateFormat.format(new Date(run.date)) + " · " + groups.size + " stacks", run.id)));
    runSelect.value = latestFirst.reduce((best, sample) => sample.groups.size > best.groups.size ? sample : best).run.id;
    const buttons = [...endpoint.names].sort((a, b) =>
      Number(b === "kotlin-reactor") - Number(a === "kotlin-reactor") || variantInfo(a).label.localeCompare(variantInfo(b).label));
    series = buttons.map((name) => {
      const info = variantInfo(name);
      const button = make("button", "", "framework-button " + info.color);
      button.type = "button";
      button.setAttribute("aria-pressed", "true");
      button.append(make("i", "", "swatch"), make("span", info.label));
      button.addEventListener("click", () => {
        button.setAttribute("aria-pressed", String(button.getAttribute("aria-pressed") !== "true"));
        draw();
      });
      function highlight(value) {
        highlighted = value;
        draw();
      }
      button.addEventListener("mouseenter", () => highlight(name));
      button.addEventListener("mouseleave", () => highlight(null));
      button.addEventListener("focus", () => highlight(name));
      button.addEventListener("blur", () => highlight(null));
      const colorGroup = Object.keys(variants).filter((variant) => variants[variant].color === info.color);
      return { name, button, dash: dashes[colorGroup.indexOf(name)] ?? [] };
    });
    element("framework-series").replaceChildren(...series.map(({ button }) => button));
    for (const entry of series) entry.color = getComputedStyle(entry.button).getPropertyValue("--series").trim();
    series.reverse(); // Paint NativeServe last.
    visibility.hidden = !endpoint.names.has("kotlin-reactor") || series.length < 2;
    element("framework-caveat").textContent =
      (samples.some(({ run }) => run.ci_run_id) ? "Exploratory CI on matching runner CPU models. " : "")
      + "Points show measured medians. Each line connects that framework’s measured runs.";
    updateReadings();
  }

  function changeProfile() {
    const profile = profiles[Number(profileSelect.value)];
    const previous = endpointSelect.value;
    element("framework-workload").textContent = workloadLabel(profile);
    endpointSelect.replaceChildren(...Array.from(profile.endpoints.keys(), (endpoint) => new Option(endpoint)));
    if (profile.endpoints.has(previous)) endpointSelect.value = previous;
    changeEndpoint();
  }

  profileSelect.replaceChildren(...profiles.map((profile, index) => new Option(profileLabel(profile), index)));
  metricSelect.replaceChildren(...metricOptions());
  profileSelect.addEventListener("change", changeProfile);
  endpointSelect.addEventListener("change", changeEndpoint);
  metricSelect.addEventListener("change", updateReadings);
  runSelect.addEventListener("change", updateReadings);
  details.addEventListener("toggle", draw);
  visibility.addEventListener("click", () => {
    const showAll = series.some(({ button }) => button.getAttribute("aria-pressed") === "false");
    for (const { name, button } of series) {
      button.setAttribute("aria-pressed", String(showAll || name === "kotlin-reactor"));
    }
    draw();
  });
  element("framework-history").hidden = false;
  changeProfile();
  new ResizeObserver(draw).observe(canvas);
}

function start() {
  const runs = data?.runs ?? [];
  if (!runs.length) {
    element("empty").hidden = false;
    element("details-link").hidden = true;
    element("history-link").hidden = true;
    element("framework-history-link").hidden = true;
    return;
  }

  const runSelect = element("run-select");
  const endpointSelect = element("endpoint");
  const buttons = [...element("metrics").querySelectorAll("button")];
  const series = new Map();
  for (const run of runs) {
    const key = run.scope && run.ci_run_id
      ? [run.ci_run_id, run.ci_run_attempt, run.machine].join("/")
      : run.id;
    if (!series.has(key)) series.set(key, []);
    series.get(key).push(run);
  }
  let metric = "rps";
  let run;

  element("repository").href = data.repository;
  const suites = { expanded: "Expanded comparisons", core: "Core comparisons", other: "Other measurements" };
  const options = Object.fromEntries(Object.entries(suites).map(([key, label]) => {
    const group = make("optgroup");
    group.label = label;
    return [key, group];
  }));
  let latestFullComparison;
  for (const [key, entries] of series) {
    const [latest] = entries;
    const suite = latest.suite in suites ? latest.suite : "other";
    const stackCount = new Set(entries.flatMap((entry) => entry.groups.map((group) => group.variant))).size;
    const label = [latest.note.label, stackCount + " stacks", dateFormat.format(new Date(latest.date))]
      .filter(Boolean).join(" · ");
    options[suite].append(new Option(label, key));
    if (!latestFullComparison && suite === "expanded"
      && (!latest.scope || entries.length === 3)
      && entries.every((entry) => !entry.incomplete && entry.valid === entry.total)) {
      latestFullComparison = key;
    }
  }
  runSelect.replaceChildren(...Object.values(options).filter((group) => group.childElementCount));
  if (latestFullComparison) runSelect.value = latestFullComparison;

  function showComparison() {
    const endpoint = endpointSelect.value;
    const groups = sortComparison(run.groups.filter((group) => group.endpoint === endpoint), metric);
    renderChart(groups, metric);
    renderTable(groups, endpoint);
  }

  function updateEndpoint() {
    const entries = series.get(runSelect.value);
    run = entries.find((entry) => entry.scope === endpointSelect.value) ?? entries[0];
    element("run-kind").textContent = run.note.kind ?? (run.local ? "Local benchmark" : "Benchmark");
    element("run-context").textContent = [
      run.ci_run_id ? "GitHub-hosted Linux" : "",
      run.scope && run.ci_run_id ? "Separate VM per endpoint" : "",
      run.local ? "Same-host load generator" : "",
    ].filter(Boolean).join(" · ");
    element("trial-count").textContent = run.valid + " / " + run.total + " valid trials" + (run.incomplete ? " · incomplete run" : "");
    element("trial-count").classList.toggle("incomplete", run.incomplete || run.valid < run.total);
    renderMethod(run);
    showComparison();
  }

  function updateRun() {
    const previous = endpointSelect.value;
    const entries = series.get(runSelect.value);
    const endpoints = [...new Set(entries.flatMap((entry) => entry.groups.map((group) => group.endpoint)))].sort();
    endpointSelect.replaceChildren(...endpoints.map((endpoint) => new Option(endpoint)));
    endpointSelect.disabled = endpoints.length < 2;
    if (endpoints.includes(previous)) endpointSelect.value = previous;
    updateEndpoint();
  }

  buttons.forEach((button) => button.addEventListener("click", () => {
    metric = button.dataset.metric;
    buttons.forEach((choice) => choice.setAttribute("aria-pressed", String(choice === button)));
    showComparison();
  }));
  runSelect.addEventListener("change", updateRun);
  endpointSelect.addEventListener("change", updateEndpoint);
  updateRun();
  element("run-picker").hidden = false;
  element("dashboard").hidden = false;
  startFrameworkHistory(runs);
  startHistory(runs);
}

start();
