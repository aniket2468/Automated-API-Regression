"use strict";
const $ = (id) => document.getElementById(id);
let state = { specs: [], suites: [], runs: [] };
let editing = null;
let watchedRun = null;
$("base-url").value = window.location.origin + "/demo-api";
const terminal = new Set([
  "PASSED",
  "FAILED",
  "ERROR",
  "SKIPPED",
  "INTERRUPTED",
]);
function node(tag, text, className) {
  const element = document.createElement(tag);
  if (text !== undefined) element.textContent = text;
  if (className) element.className = className;
  return element;
}
function notice(message, error = false) {
  $("notice").textContent = message;
  $("notice").className = error ? "error" : "";
  $("notice").hidden = false;
}
async function api(path, options = {}) {
  if (options.body && !(options.body instanceof FormData)) {
    options.headers = { "Content-Type": "application/json" };
    options.body = JSON.stringify(options.body);
  }
  const response = await fetch("/api" + path, options);
  const result = await response.json();
  if (!response.ok) throw new Error(result.message || "Request failed");
  return result;
}
function options(id, values, label) {
  const current = $(id).value;
  $(id).replaceChildren(
    ...values.map((value) => {
      const option = node("option", label(value));
      option.value = value.id;
      return option;
    }),
  );
  if (values.some((value) => value.id === current)) $(id).value = current;
}
function updateImpactSuites() {
  options(
    "impact-suite",
    state.suites.filter((s) => s.specId === $("old-spec").value),
    (s) => s.name,
  );
}
async function refresh() {
  const [specs, suites, runs] = await Promise.all([
    api("/specs"),
    api("/suites"),
    api("/runs"),
  ]);
  state = { specs, suites, runs };
  const completedRun = runs.find(
    (r) => r.id === watchedRun && terminal.has(r.status),
  );
  if (completedRun) {
    notice(
      `Run ${completedRun.id.slice(0, 8)} completed: ${completedRun.status}. ${completedRun.passed} passed, ${completedRun.failed} failed, ${completedRun.skipped} skipped.`,
      completedRun.failed > 0 ||
        ["ERROR", "INTERRUPTED"].includes(completedRun.status),
    );
    watchedRun = null;
  }
  $("spec-count").textContent = specs.length;
  $("suite-count").textContent = suites.length;
  $("run-count").textContent = runs.length;
  const executed = runs.reduce((n, r) => n + r.passed + r.failed, 0);
  $("pass-rate").textContent = executed
    ? Math.round((100 * runs.reduce((n, r) => n + r.passed, 0)) / executed) +
      "%"
    : "—";
  for (const id of ["generate-spec", "old-spec", "new-spec"])
    options(
      id,
      specs,
      (s) => `${s.name} · v${s.version} · ${s.id.slice(0, 8)}`,
    );
  options("run-suite", suites, (s) => `${s.name} · ${s.tests.length} tests`);
  updateImpactSuites();
  $("spec-list").replaceChildren(
    ...specs.slice(0, 5).map((s) => {
      const item = node("div", `${s.name} · v${s.version}`, "item");
      item.append(
        node(
          "small",
          `${s.operations} operations · ${new Date(s.createdAt).toLocaleString()}`,
        ),
      );
      return item;
    }),
  );
  $("run-list").replaceChildren(
    ...runs.map((r) => {
      const row = node("tr");
      const name = node("td", r.suiteName);
      name.append(node("small", new Date(r.createdAt).toLocaleString()));
      const status = node("td");
      status.append(node("span", r.status, "badge " + r.status));
      const linkCell = node("td");
      const link = node("a", "Report ↗");
      link.href = "/reports/" + encodeURIComponent(r.id);
      linkCell.append(link);
      row.append(
        name,
        status,
        node("td", r.passed),
        node("td", r.failed),
        node("td", r.skipped),
        linkCell,
      );
      return row;
    }),
  );
  if (!runs.length) {
    const row = node("tr");
    const cell = node(
      "td",
      "No runs yet. Import a specification and generate your first suite.",
    );
    cell.colSpan = 6;
    row.append(cell);
    $("run-list").append(row);
  }
}
function form(id, handler) {
  $(id).addEventListener("submit", async (event) => {
    event.preventDefault();
    const button = $(id).querySelector('button[type="submit"]');
    if (button) button.disabled = true;
    try {
      await handler();
    } catch (error) {
      if (id === "edit-form") {
        $("edit-error").textContent = error.message;
        $("edit-error").hidden = false;
      } else {
        notice(error.message, true);
      }
    } finally {
      if (button) button.disabled = false;
    }
  });
}
form("import-form", async () => {
  const file = $("spec-file").files[0];
  if (!file || file.size > 2_000_000)
    throw new Error("Choose a specification file smaller than 2 MB.");
  const data = new FormData();
  data.append("name", $("spec-name").value);
  data.append("file", file);
  const spec = await api("/specs/upload", { method: "POST", body: data });
  await refresh();
  $("generate-spec").value = spec.id;
  notice(`Imported ${spec.name} v${spec.version}. Generate a test suite next.`);
});
form("generate-form", async () => {
  const suite = await api("/suites", {
    method: "POST",
    body: { specId: $("generate-spec").value, name: $("suite-name").value },
  });
  await refresh();
  $("run-suite").value = suite.id;
  review();
  notice(
    `Generated ${suite.tests.length} tests. Review disabled tests and request inputs before running.`,
  );
});
async function startRun(suiteId, compareToSpecId) {
  const run = await api("/runs", {
    method: "POST",
    body: {
      suiteId,
      baseUrl: $("base-url").value,
      parallelism: Number($("parallelism").value),
      timeoutMs: Number($("timeout").value),
      ...(compareToSpecId ? { compareToSpecId } : {}),
    },
  });
  watchedRun = run.id;
  notice(
    `Run ${run.id.slice(0, 8)} queued. Results will update automatically.`,
  );
  await refresh();
  $("history").scrollIntoView({ behavior: "smooth" });
}
form("run-form", () => startRun($("run-suite").value));
function review() {
  const suite = state.suites.find((s) => s.id === $("run-suite").value);
  $("test-list").replaceChildren();
  if (!suite) return;
  for (const test of suite.tests) {
    const item = node("div", test.operation, "item");
    item.append(
      node(
        "small",
        test.config.enabled
          ? `Enabled · HTTP ${test.expectedStatus} · ${test.config.maxLatencyMs} ms budget`
          : `Disabled · ${test.config.disabledReason}`,
      ),
    );
    const button = node("button", "Configure", "text-button");
    button.type = "button";
    button.addEventListener("click", () => {
      editing = { suiteId: suite.id, testId: test.id };
      $("edit-operation").textContent = test.operation;
      $("edit-error").hidden = true;
      $("config-json").value = JSON.stringify(test.config, null, 2);
      $("editor").showModal();
    });
    item.append(button);
    $("test-list").append(item);
  }
}
$("review-tests").addEventListener("click", review);
$("run-suite").addEventListener("change", review);
$("close-editor").addEventListener("click", () => $("editor").close());
form("edit-form", async () => {
  const config = JSON.parse($("config-json").value);
  await api(`/suites/${editing.suiteId}/tests/${editing.testId}`, {
    method: "PUT",
    body: config,
  });
  $("editor").close();
  await refresh();
  review();
  notice("Test configuration saved.");
});
$("old-spec").addEventListener("change", updateImpactSuites);
form("compare-form", async () => {
  const suiteId = $("impact-suite").value,
    candidate = $("new-spec").value;
  const query = new URLSearchParams({
    oldSpecId: $("old-spec").value,
    newSpecId: candidate,
    suiteId,
  });
  const impact = await api("/changes?" + query);
  const panel = $("impact-results");
  panel.replaceChildren();
  panel.append(
    node(
      "p",
      `${impact.changes.length} changed operations · ${impact.affectedTestIds.length} affected existing tests · ${impact.addedOperations.length} new operations`,
    ),
  );
  for (const change of impact.changes) {
    const item = node("div", `${change.kind} · ${change.operation}`, "item");
    for (const reason of change.reasons) item.append(node("small", reason));
    panel.append(item);
  }
  if (impact.addedOperations.length)
    panel.append(
      node(
        "p",
        "Generate a suite from the candidate specification to cover new operations.",
        "hint",
      ),
    );
  if (impact.affectedTestIds.length) {
    const button = node("button", "Run affected baseline tests →");
    button.type = "button";
    button.addEventListener("click", async () => {
      button.disabled = true;
      try {
        await startRun(suiteId, candidate);
      } catch (e) {
        notice(e.message, true);
      } finally {
        button.disabled = false;
      }
    });
    panel.append(button);
    panel.append(
      node(
        "p",
        "Uses the target URL above and keeps baseline expectations to detect compatibility regressions.",
        "hint",
      ),
    );
  }
});
$("refresh").addEventListener("click", () =>
  refresh().catch((e) => notice(e.message, true)),
);
let refreshing = false;
setInterval(async () => {
  if (refreshing || !state.runs.some((r) => !terminal.has(r.status))) return;
  refreshing = true;
  try {
    await refresh();
  } catch (e) {
    notice(e.message, true);
  } finally {
    refreshing = false;
  }
}, 2000);
refresh().catch((e) => notice(e.message, true));
