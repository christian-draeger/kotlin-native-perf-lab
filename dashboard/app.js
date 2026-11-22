"use strict";

const data = window.benchmarkData;
const element = (id) => document.getElementById(id);
const metrics = {
  rps: { name: "Durchsatz", unit: "Req/s", decimals: 0, direction: "Höher ist besser" },
  p50: { unit: "ms", decimals: 3 },
  p95: { unit: "ms", decimals: 3 },
  p99: { name: "P99-Latenz", unit: "ms", decimals: 3, direction: "Niedriger ist besser" },
  cpu: { name: "CPU-Aufwand", unit: "s / 1 Mio. Req.", decimals: 1, direction: "Niedriger ist besser" },
  rss: { name: "Spitzen-RSS", unit: "MiB", decimals: 1, direction: "Niedriger ist besser" },
};
const variants = {
  "ktor-jvm": { label: "Ktor/JVM", detail: "CIO · HotSpot", color: "jvm" },
  "ktor-native": { label: "Ktor/Native", detail: "CIO · Kotlin/Native", color: "native" },
  "rust-axum": { label: "Rust/Axum", detail: "Axum · Tokio", color: "rust" },
};
const dateFormat = new Intl.DateTimeFormat("de-DE", {
  dateStyle: "medium", timeStyle: "short",
});
const format = (value, key) => new Intl.NumberFormat("de-DE", {
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

function fileLink(label, path, directory = false) {
  const link = make("a", label);
  const kind = directory ? "tree" : "blob";
  link.href = [data.repository, kind, data.revision, path].join("/");
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
      reading.append(make("span", "Keine gültigen Läufe"));
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
    ["Verbindungen", "connections", ""],
    ["wrk-Threads", "threads", ""],
    ["Warm-up", "warmup_s", " s"],
    ["Messfenster (Ziel)", "target_duration_s", " s"],
  ].flatMap(([label, key, unit]) => {
    const values = run.parameters[key];
    return [make("dt", label), make("dd", values.length ? values.join(" / ") + unit : "—")];
  });
  element("parameters").replaceChildren(...parameters);
  const caveat = run.note.note ?? (run.local ? "wrk und die Server teilten sich einen Host." : "");
  element("caveat").textContent = caveat;
  element("caveat").hidden = !caveat;
  const sources = [
    fileLink("CSV", run.folder + "/results.csv"),
    fileLink("Rohdaten", run.folder, true),
  ];
  if (run.has_environment) {
    sources.push(fileLink("Umgebung", run.folder + "/environment.txt"));
  }
  sources.push(...(run.note.links ?? []).map((link) => fileLink(link.label, link.path)));
  if (/^[0-9a-f]{40}$/.test(run.sha ?? "")) {
    const commit = make("a", run.sha.slice(0, 7));
    commit.href = data.repository + "/commit/" + run.sha;
    commit.setAttribute("aria-label", "Gemessener Commit " + run.sha.slice(0, 7));
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
    element("run-kind").textContent = run.note.kind ?? (run.local ? "Lokale Messung" : "Benchmark");
    element("run-context").textContent = run.local ? "Lastgenerator auf demselben Host" : "";
    element("trial-count").textContent = run.valid + " / " + run.total + " gültige Läufe";
    element("trial-count").classList.toggle("incomplete", run.valid < run.total);
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
