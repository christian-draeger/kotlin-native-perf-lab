(() => {
  const target = document.querySelector("#cli-terminal-target");
  if (!target) return;

  const command = document.querySelector("#cli-install-command");
  const archiveCommand = document.querySelector("#cli-archive-command");
  const copy = document.querySelector("#cli-copy");
  const status = document.querySelector("#cli-copy-status");
  const directory = document.querySelector("#cli-directory");
  const links = document.querySelectorAll("[data-cli-target]");
  const canCopy = Boolean(copy && status && navigator.clipboard?.writeText);
  const start = command.hasAttribute("data-cli-install-only")
    ? ""
    : " &&\n./ktserve serve - <<<'{\"status\":\"ok\"}'";
  const release = "cli-preview-0.1.7";
  const releaseUrl = `https://github.com/christian-draeger/ktserve/releases/download/${release}`;
  const groups = [
    ["HTTP", [
      ["linux-x86_64", "Linux x86-64"],
      ["linux-aarch64", "Linux ARM64"],
      ["macos-arm64", "Apple Silicon macOS"],
    ]],
    ["Direct HTTPS", [
      ["linux-x86_64-https", "Linux x86-64 HTTPS"],
      ["linux-aarch64-https", "Linux ARM64 HTTPS"],
    ]],
  ];
  const downloads = new Map();

  for (const [label, platforms] of groups) {
    const group = document.createElement("optgroup");
    group.label = label;
    for (const [key, name] of platforms) {
      group.append(new Option(name, key));
      downloads.set(key, `${releaseUrl}/ktserve-${release}-${key}`);
    }
    target.append(group);
  }

  for (const link of links) {
    const key = link.dataset.cliTarget;
    link.href = downloads.get(key);
    link.addEventListener("click", () => {
      target.value = key;
      showCommand();
    });
  }

  function showCommand() {
    const url = downloads.get(target.value);
    for (const link of links) {
      const selected = link.dataset.cliTarget === target.value;
      link.classList.toggle("button-primary", selected);
      link.classList.toggle("button-secondary", !selected);
      if (selected) link.setAttribute("aria-current", "true");
      else link.removeAttribute("aria-current");
    }
    command.textContent = url ? `curl -fsSL ${url} -o ktserve && chmod +x ktserve${start}` : "";
    command.closest(".cli-terminal-command").hidden = !url;
    if (archiveCommand) {
      archiveCommand.textContent = url ? `curl -fsSL ${url}.tar.gz | tar -xzf -` : "";
      archiveCommand.closest(".cli-terminal-command").hidden = !url;
    }
    if (copy) copy.hidden = !canCopy || !url;
    if (status) status.textContent = "";
  }

  async function detectTarget() {
    const ua = navigator.userAgentData;
    if (ua?.mobile || (ua?.platform && !["Linux", "macOS"].includes(ua.platform))) return "";
    if (ua?.getHighEntropyValues) {
      try {
        const { platform, architecture, bitness } = await ua.getHighEntropyValues(["architecture", "bitness"]);
        if (architecture && bitness) {
          if (bitness !== "64") return "";
          if (platform === "Linux" && architecture === "x86") return "linux-x86_64";
          if (platform === "Linux" && architecture === "arm") return "linux-aarch64";
          if (platform === "macOS" && architecture === "arm") return "macos-arm64";
          return "";
        }
      } catch {
        // Some browsers block architecture hints.
      }
    }

    const agent = navigator.userAgent;
    if (/\b(Android|CrOS|Mobile)\b/i.test(agent)) return "";
    if (/\bLinux (?:aarch64|arm64)\b/i.test(agent)) return "linux-aarch64";
    // Reduced Chromium user agents use Linux x86_64 on other architectures too.
    if (/\bLinux (?:x86_64|amd64)\b/i.test(agent) && !/\b(?:Chrome|Chromium)\//.test(agent)) {
      return "linux-x86_64";
    }
    return "";
  }

  target.addEventListener("change", showCommand);
  showCommand();
  target.parentElement.hidden = false;
  const useHttps = location.hash === "#direct-https";
  if (useHttps && directory) directory.open = true;
  detectTarget().then(key => {
    if (!key || target.value) return;
    const preferred = useHttps ? `${key}-https` : key;
    if (downloads.has(preferred)) {
      target.value = preferred;
      showCommand();
    }
  });

  if (canCopy) {
    copy.addEventListener("click", async () => {
      try {
        await navigator.clipboard.writeText(command.textContent);
        status.textContent = "Copied.";
      } catch {
        status.textContent = "Select the command to copy it.";
      }
    });
  }
})();
