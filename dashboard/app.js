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
  "ktserve": { label: "ktServe", detail: "Kotlin/Native · HTTP server", color: "ktserve" },
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
  "ktor-native-cio": { label: "Ktor with CIO", detail: "Kotlin/Native", color: "kotlin" },
  "ktor-native-ktserve": { label: "Ktor with ktServe", detail: "Kotlin/Native · epoll", color: "ktserve" },
  "ktor-native-ktserve-eventfd": { label: "Ktor with ktServe eventfd", detail: "Kotlin/Native · epoll", color: "ktserve" },
  "ktor-jvm-cio": { label: "Ktor with CIO", detail: "JVM", color: "kotlin" },
  "ktor-jvm-netty": { label: "Ktor with Netty", detail: "JVM", color: "kotlin" },
  "ktor-jvm-ktserve": { label: "Ktor with ktServe", detail: "JVM · JDK NIO", color: "ktserve" },
  "spring-webflux-reactor-netty": { label: "WebFlux with Reactor Netty", detail: "Spring Boot · JVM", color: "kotlin" },
  "spring-webflux-ktserve": { label: "WebFlux with ktServe", detail: "Spring Boot · JVM · JDK NIO", color: "ktserve" },
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

function renderChart(container, groups, metric) {
  const info = metrics[metric];
  const scale = Math.max(1, ...groups.map((group) => group.stats?.[metric].max ?? 0)) * 1.06;
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
        "Range " + [format(stats.min, metric), format(stats.max, metric)].join("–") + " " + info.unit,
        "run-range"));
    }
    return row;
  });
  container.replaceChildren(...rows);
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
    const values = run.parameters[key] ?? [];
    return [make("dt", label), make("dd", values.length ? values.join(" / ") + unit : "—")];
  });
  element("parameters").replaceChildren(...parameters);
  const caveat = run.note.note ?? (run.local ? "wrk and the servers shared one host." : "");
  element("caveat").textContent = caveat;
  element("caveat").hidden = !caveat;
  element("source-links").replaceChildren(...runSources(run));
}

function runSources(run) {
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
  return sources;
}

const timeCharts = new WeakMap();
const reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)");

function palette(name) {
  return getComputedStyle(document.documentElement).getPropertyValue(name).trim();
}

function chartPoint(run, values) {
  return { x: Date.parse(run.date), y: values.median, min: values.min, max: values.max };
}

const historyMarks = {
  id: "historyMarks",
  beforeDatasetsDraw(chart, _args, options) {
    const { ctx, chartArea, scales } = chart;
    if (options.selectedTime == null && !options.ranges) return;
    ctx.save();
    ctx.beginPath();
    ctx.rect(chartArea.left, chartArea.top, chartArea.width, chartArea.height);
    ctx.clip();
    if (options.selectedTime != null) {
      const left = scales.x.getPixelForValue(options.selectedTime);
      ctx.strokeStyle = "#74658b";
      ctx.setLineDash([3, 3]);
      ctx.beginPath();
      ctx.moveTo(left, chartArea.top);
      ctx.lineTo(left, chartArea.bottom);
      ctx.stroke();
      ctx.setLineDash([]);
    }
    if (options.ranges) {
      ctx.strokeStyle = "#f3ad92a0";
      for (const point of chart.data.datasets[0].data) {
        const left = scales.x.getPixelForValue(point.x);
        const top = scales.y.getPixelForValue(point.max);
        const bottom = scales.y.getPixelForValue(point.min);
        ctx.beginPath();
        ctx.moveTo(left, top);
        ctx.lineTo(left, bottom);
        ctx.moveTo(left - 4, top);
        ctx.lineTo(left + 4, top);
        ctx.moveTo(left - 4, bottom);
        ctx.lineTo(left + 4, bottom);
        ctx.stroke();
      }
    }
    ctx.restore();
  },
};

function drawTimeChart(canvas, datasets, metric, { ranges = false, selectedTime = null } = {}) {
  if (!window.Chart || !datasets.length) return;
  const points = datasets.flatMap((dataset) => dataset.data);
  const first = Math.min(...points.map((point) => point.x));
  const last = Math.max(...points.map((point) => point.x));
  const min = Math.min(...points.map((point) => ranges ? point.min : point.y));
  const max = Math.max(...points.map((point) => ranges ? point.max : point.y));
  const hour = 60 * 60 * 1000;
  const timePadding = Math.max(5 * 60 * 1000, (last - first) * 0.035);
  const valuePadding = Math.max((max - min) * 0.125, max * 0.025, 0.001);
  const axisDate = (time) => {
    if (last - first >= 48 * hour) return shortDate.format(time);
    if (shortDate.format(first) === shortDate.format(last)) return shortTime.format(time);
    return shortDate.format(time) + " " + shortTime.format(time);
  };
  const text = palette("--muted");
  const grid = palette("--chart-grid");
  const options = {
    responsive: true,
    maintainAspectRatio: false,
    parsing: false,
    animation: reducedMotion.matches ? false : { duration: 420 },
    layout: { padding: { top: 10, right: 8 } },
    interaction: { axis: "xy", mode: "nearest", intersect: false },
    plugins: {
      legend: { display: false },
      historyMarks: { ranges, selectedTime },
      tooltip: {
        backgroundColor: "#242235",
        borderColor: "#615778",
        borderWidth: 1,
        padding: 11,
        callbacks: {
          title: ([item]) => utcDateFormat.format(item.raw.x) + " UTC",
          label: ({ dataset, raw }) => dataset.label + " · " + format(raw.y, metric) + " " + metrics[metric].unit,
          afterLabel: ({ raw }) => ranges ? "Trial range " + format(raw.min, metric) + "–"
            + format(raw.max, metric) + " " + metrics[metric].unit : "",
        },
      },
    },
    scales: {
      x: {
        type: "linear",
        min: first - timePadding,
        max: last + timePadding,
        afterBuildTicks(scale) {
          // Use UTC dates drawn from the actual run span, rather than rounding epoch milliseconds.
          const times = first === last ? [first] : scale.width < 430 ? [first, last] : [first, (first + last) / 2, last];
          scale.ticks = times.map((value) => ({ value }));
        },
        ticks: { color: text, callback: axisDate, maxRotation: 0, padding: 7, align: "inner" },
        grid: { color: grid, drawTicks: false },
        border: { color: grid },
      },
      y: {
        suggestedMin: Math.max(0, min - valuePadding),
        suggestedMax: max + valuePadding,
        ticks: { color: text, callback: (value) => format(value, metric), maxTicksLimit: 6, padding: 8 },
        grid: { color: grid, drawTicks: false },
        border: { display: false },
      },
    },
  };
  const chart = timeCharts.get(canvas);
  if (chart) {
    chart.data.datasets = datasets;
    chart.options = options;
    chart.update("none");
  } else {
    timeCharts.set(canvas, new window.Chart(canvas, { type: "line", data: { datasets }, options, plugins: [historyMarks] }));
  }
}

function drawHistory(canvas, points, metric) {
  const accent = palette("--ktserve-accent");
  drawTimeChart(canvas, [{
    label: "ktServe",
    data: points.map(({ run, values }) => chartPoint(run, values)),
    borderColor: accent,
    backgroundColor({ chart }) {
      if (!chart.chartArea) return "transparent";
      const fill = chart.ctx.createLinearGradient(0, chart.chartArea.top, 0, chart.chartArea.bottom);
      fill.addColorStop(0, "#ff8e642d");
      fill.addColorStop(1, "#ff8e6400");
      return fill;
    },
    pointBackgroundColor: accent,
    pointBorderWidth: 0,
    pointRadius: 3,
    pointHoverRadius: 6,
    borderWidth: 2.5,
    fill: "start",
  }], metric, { ranges: true });
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
      const group = run.groups.find((item) => item.variant === "ktserve" && item.endpoint === endpointSelect.value);
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
  element("history-controls").hidden = false;
  changeProfile();
}

function drawFrameworkHistory(canvas, samples, series, metric, selectedRun, highlighted) {
  const datasets = series.map(({ name, color, dash }) => {
    const own = name === "ktserve";
    const visibleColor = highlighted && highlighted !== name ? color + "42" : color;
    return {
      label: variantInfo(name).label,
      data: samples.flatMap(({ run, groups }) => {
        const values = groups.get(name)?.stats[metric];
        return values ? [chartPoint(run, values)] : [];
      }),
      borderColor: visibleColor,
      pointBackgroundColor: visibleColor,
      borderDash: dash,
      borderWidth: own ? 2.75 : 1.5,
      pointBorderWidth: 0,
      pointRadius: own ? 3.75 : 2.35,
      pointHoverRadius: 5,
      order: own ? 0 : 1,
    };
  });
  drawTimeChart(canvas, datasets, metric, { selectedTime: selectedRun ? Date.parse(selectedRun.date) : null });
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
  const plot = element("framework-plot");
  const details = element("framework-history").querySelector("details");
  const visibility = element("framework-visibility");
  const dashes = [[], [8, 4], [2, 4], [8, 3, 2, 3], [4, 3], [11, 4, 4, 4], [1, 3], [14, 5], [5, 3, 2, 3, 2, 3]];
  let samples, series;
  let highlighted;

  function draw() {
    const visible = series.filter(({ button }) => button.getAttribute("aria-pressed") === "true");
    plot.hidden = !visible.length;
    element("framework-empty").hidden = Boolean(visible.length);
    visibility.textContent = visible.length === series.length ? "ktServe only" : "Show all";
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
      Number(b === "ktserve") - Number(a === "ktserve") || variantInfo(a).label.localeCompare(variantInfo(b).label));
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
    visibility.hidden = !endpoint.names.has("ktserve") || series.length < 2;
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
      button.setAttribute("aria-pressed", String(showAll || name === "ktserve"));
    }
    draw();
  });
  element("framework-history").hidden = false;
  changeProfile();
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
  const suites = {
    expanded: "Expanded comparisons", core: "Core comparisons",
    application: "Application workloads", other: "Other measurements",
  };
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
    element("metric-name").textContent = metrics[metric].name;
    element("metric-direction").textContent = metrics[metric].direction;
    renderChart(element("chart"), groups, metric);
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
  element("details").hidden = false;
  element("ktserve-history").hidden = false;
  startFrameworkHistory(runs);
  startHistory(runs);
}

function startIntegration() {
  const families = {
    "ktor-native": "Ktor · Kotlin/Native",
    "ktor-jvm": "Ktor · JVM",
    "spring-webflux": "Spring Boot WebFlux · JVM",
  };
  const runs = data?.integration_runs ?? [];
  const available = Object.keys(families).filter((family) => runs.some((run) => run.integration === family));
  element("integration-empty").hidden = Boolean(available.length);
  if (!available.length) return;

  const familySelect = element("integration-family");
  const runSelect = element("integration-run");
  const endpointSelect = element("integration-endpoint");
  const metricSelect = element("integration-metric");
  const operationLabels = {
    json: "GET /json", catalog: "GET /catalog/{id}", upload_1k: "POST /upload · 1 KiB",
    upload_24k: "POST /upload · 24 KiB", upload_64k: "POST /upload · 64 KiB", download: "GET /download",
  };
  let familyRuns;
  let run;
  function draw() {
    const key = metricSelect.value;
    const endpoint = endpointSelect.value;
    const groups = sortComparison(run.groups.filter((group) => group.endpoint === endpoint), key);
    const valid = groups.reduce((total, group) => total + group.valid, 0);
    const trials = groups.reduce((total, group) => total + group.total, 0);
    element("integration-axis").textContent = metrics[key].name + " · " + metrics[key].direction;
    element("integration-context").textContent = valid + " / " + trials + " valid trials"
      + (run.incomplete ? " · incomplete run" : "");
    renderChart(element("integration-chart"), groups, key);
    const operationRows = Object.entries(operationLabels).flatMap(([name, label]) => groups.flatMap((group) => {
      const observation = group.operations?.[name];
      if (!observation) return [];
      const row = make("tr");
      const variant = variantInfo(group.variant).label;
      row.append(make("th", label), make("td", variant), make("td", format(observation.rps, "rps")),
        make("td", format(observation.p99, "p99")));
      return [row];
    }));
    element("integration-operations").hidden = !operationRows.length;
    element("integration-operation-rows").replaceChildren(...operationRows);
  }

  function changeRun() {
    const previousTool = run?.load_tool;
    run = familyRuns.find((entry) => entry.id === runSelect.value);
    if (run.load_tool === "k6" && previousTool !== "k6") metricSelect.value = "p99";
    const previous = endpointSelect.value;
    const endpoints = [...new Set(run.groups.map((group) => group.endpoint))];
    endpointSelect.replaceChildren(...endpoints.map((endpoint) => new Option(endpoint)));
    if (endpoints.includes(previous)) endpointSelect.value = previous;
    else if (endpoints.includes("/json")) endpointSelect.value = "/json";
    const conditions = [
      run.ci_run_id ? "Exploratory hosted CI" : "",
      run.note.host, run.os, run.machine,
      run.load_tool === "k6" ? "k6 · fixed arrival rate · mixed HTTP/1.1 traffic" : "wrk · fixed concurrency",
      ...[
        ["connections", " connections"],
        ["threads", " wrk threads"],
        ["vus_per_scenario", " max VUs per scenario"],
        ["precondition_s", " s cold-start load"],
        ["warmup_s", " s warm-up"],
        ["target_duration_s", " s trial"],
      ].flatMap(([key, label]) => run.parameters[key]?.length ? [run.parameters[key].join(" / ") + label] : []),
    ];
    const caveat = run.note.note ?? (run.local ? "The load generator and server shared one host." : "");
    element("integration-workload").textContent = conditions.filter(Boolean).join(" · ") + (caveat ? ". " + caveat : "");
    element("integration-links").replaceChildren(...runSources(run));
    draw();
  }

  function changeFamily() {
    familyRuns = runs.filter((entry) => entry.integration === familySelect.value);
    runSelect.replaceChildren(...familyRuns.map((entry) => new Option([
      entry.experiment === "epoll-application-wakeup" ? `wrk · ${entry.groups[0]?.endpoint ?? "streaming"} wake-up pilot`
        : entry.load_tool === "k6" ? "k6 · mixed" : "wrk · single endpoint", entry.machine,
      utcDateFormat.format(new Date(entry.date)) + " UTC", entry.incomplete ? "Incomplete" : "",
    ].filter(Boolean).join(" · "), entry.id)));
    const latestComplete = familyRuns.find((entry) => !entry.experiment && !entry.incomplete && entry.valid === entry.total);
    if (latestComplete) runSelect.value = latestComplete.id;
    changeRun();
  }

  familySelect.replaceChildren(...available.map((family) => new Option(families[family], family)));
  metricSelect.replaceChildren(...metricOptions());
  familySelect.addEventListener("change", changeFamily);
  runSelect.addEventListener("change", changeRun);
  endpointSelect.addEventListener("change", draw);
  metricSelect.addEventListener("change", draw);
  element("integration-data").hidden = false;
  changeFamily();
}

start();
startIntegration();
