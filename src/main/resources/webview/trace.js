"use strict";
(() => {
  // src/fileLocations.ts
  function escapeHtml(value) {
    return value.replace(/[&<>"']/gu, (character) => ({
      "&": "&amp;",
      "<": "&lt;",
      ">": "&gt;",
      '"': "&quot;",
      "'": "&#39;"
    })[character] ?? character);
  }

  // webview/src/trace/main.ts
  function bootstrap() {
    const node = document.getElementById("trace-bootstrap");
    if (!node?.textContent) throw new Error("Trace bootstrap data is missing");
    return JSON.parse(node.textContent);
  }
  var { sessionId, strings: i18n } = bootstrap();
  var vscode = acquireVsCodeApi();
  vscode.setState({ sessionId });
  var state = { rows: [], projections: [], query: "" };
  var detail;
  var activeTab = "summary";
  var searchTimer;
  var collapsed = /* @__PURE__ */ new Set();
  function el(id) {
    const node = document.getElementById(id);
    if (!node) throw new Error(`Trace panel element #${id} is missing`);
    return node;
  }
  function formatTime(value) {
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return String(value);
    const pad = (number, size = 2) => String(number).padStart(size, "0");
    return `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}.${pad(date.getMilliseconds(), 3)}`;
  }
  function duration(row) {
    return row.durationMs === void 0 ? "\u2014" : `${Math.round(row.durationMs).toLocaleString()} ms`;
  }
  function tokenLabel(tokens) {
    if (!tokens) return "";
    const parts = [`in ${tokens.inputTokens}`, `out ${tokens.outputTokens}`];
    if (tokens.cacheReadTokens !== void 0) parts.push(`cache-read ${tokens.cacheReadTokens}`);
    if (tokens.cacheWriteTokens !== void 0) parts.push(`cache-write ${tokens.cacheWriteTokens}`);
    if (tokens.reasoningTokens !== void 0) parts.push(`think ${tokens.reasoningTokens}`);
    return parts.join(" \xB7 ");
  }
  function compactNumber(value) {
    return Number(value || 0).toLocaleString();
  }
  function compactDuration(value) {
    if (value === void 0) return "\u2014";
    if (value < 1e3) return `${Math.round(value)} ms`;
    if (value < 6e4) return `${(value / 1e3).toFixed(1)} s`;
    return `${Math.floor(value / 6e4)}m ${Math.round(value % 6e4 / 1e3)}s`;
  }
  function hasCollapsedAncestor(rows, index) {
    const depth = Number(rows[index]?.depth || 0);
    for (let i = index - 1; i >= 0; i -= 1) {
      const candidate = rows[i];
      if (candidate && Number(candidate.depth || 0) < depth) {
        if (collapsed.has(candidate.id)) return true;
        return hasCollapsedAncestor(rows, i);
      }
    }
    return false;
  }
  function hasChildren(rows, index) {
    return index + 1 < rows.length && Number(rows[index + 1]?.depth || 0) > Number(rows[index]?.depth || 0);
  }
  var TIMELINE_LANES = [
    ["input", "Input"],
    ["model", "Model"],
    ["tools", "Tools"]
  ];
  function renderOverview() {
    const overview = state.overview;
    el("metricDuration").textContent = compactDuration(overview?.durationMs);
    el("metricTurns").textContent = compactNumber(overview?.turns);
    el("metricCalls").textContent = compactNumber(overview?.calls);
    el("metricErrors").textContent = compactNumber(overview?.errors);
    el("metricInput").textContent = compactNumber(overview?.inputTokens);
    el("metricOutput").textContent = compactNumber(overview?.outputTokens);
    el("metricCacheRead").textContent = compactNumber(overview?.cacheReadTokens);
    el("metricCacheWrite").textContent = compactNumber(overview?.cacheWriteTokens);
    const modeButton = el("timelineMode");
    modeButton.textContent = state.timelineMode === "duration" ? i18n.duration : i18n.sequence;
    modeButton.classList.toggle("active", state.timelineMode === "duration");
    const timeline = state.timeline ?? [];
    const timelineLanes = el("timelineLanes");
    timelineLanes.innerHTML = "";
    for (const [lane, label] of TIMELINE_LANES) {
      const laneEl = document.createElement("div");
      laneEl.className = "timeline-lane";
      const labelEl = document.createElement("span");
      labelEl.className = "timeline-lane-label";
      labelEl.textContent = label;
      const trackEl = document.createElement("div");
      trackEl.className = "timeline-track";
      for (const item of timeline.filter((candidate) => candidate.lane === lane)) {
        const bar = document.createElement("div");
        bar.className = `timeline-item ${item.category}`;
        bar.dataset.timelineId = item.id;
        bar.title = `${item.eventType} \xB7 ${compactDuration(item.durationMs)} \xB7 ${item.summary}`;
        bar.style.left = `${item.left}%`;
        bar.style.width = `${item.width}%`;
        trackEl.appendChild(bar);
      }
      laneEl.appendChild(labelEl);
      laneEl.appendChild(trackEl);
      timelineLanes.appendChild(laneEl);
    }
    el("timelineStart").textContent = state.timelineStart === void 0 ? "\u2014" : formatTime(state.timelineStart);
    el("timelineEnd").textContent = state.timelineStart !== void 0 && state.timelineEnd !== void 0 ? `+${compactDuration(state.timelineEnd - state.timelineStart)}` : "\u2014";
  }
  function openFileLocation(target) {
    const link = target instanceof Element ? target.closest("[data-file-path]") : void 0;
    const path = link?.dataset.filePath;
    if (!path) return false;
    const line = Number(link?.dataset.fileLine);
    const column = link?.dataset.fileColumn === void 0 ? void 0 : Number(link.dataset.fileColumn);
    if (!Number.isSafeInteger(line) || line <= 0 || column !== void 0 && (!Number.isSafeInteger(column) || column <= 0)) return false;
    vscode.postMessage({
      type: "openFileLocation",
      path,
      line,
      ...column === void 0 ? {} : { column }
    });
    return true;
  }
  function rowMarkup(row, index, sourceRows) {
    if (hasCollapsedAncestor(sourceRows, index)) return "";
    const group = (row.turn === void 0 ? "session" : `T${row.turn}`) + (row.step === void 0 ? "" : ` \xB7 S${row.step}`);
    const meta = [row.tool?.name, row.callId, tokenLabel(row.tokens)].filter(Boolean).join(" \xB7 ");
    const summary = row.summary + (meta ? ` \xB7 ${meta}` : "");
    const summaryHtml = row.summaryHtml + (meta ? ` \xB7 ${escapeHtml(meta)}` : "");
    const toggle = hasChildren(sourceRows, index) ? `<button class="tree-toggle secondary" data-toggle-row="${escapeHtml(row.id)}" title="${collapsed.has(row.id) ? i18n.expand : i18n.collapse}">${collapsed.has(row.id) ? "+" : "\u2212"}</button>` : "";
    const classes = `trace-row ${escapeHtml(row.category)} depth-${Math.min(8, Number(row.depth || 0))}` + (row.error ? " error" : "") + (state.selectedId === row.id ? " selected" : "");
    return `<div class="${classes}" data-row-id="${escapeHtml(row.id)}"><div class="seq">#${Number(state.offset || 0) + index + 1} \xB7 ${escapeHtml(String(row.seq))}${row.endSeq === void 0 ? "" : `\u2192${escapeHtml(String(row.endSeq))}`}</div><div class="event">${toggle}${escapeHtml(row.eventType)}</div><div class="meta">${escapeHtml(group)}</div><div class="summary" title="${escapeHtml(summary)}">${summaryHtml}${row.error ? ` \xB7 ${row.errorHtml}` : ""}</div><div class="time">${escapeHtml(formatTime(row.time))}<br>${escapeHtml(duration(row))}</div></div>`;
  }
  function render() {
    el("title").textContent = `DSH Trace: ${state.title || state.sessionId || ""}`;
    const status = state.status;
    el("dot").className = "dot " + (status?.error || state.error ? "error" : status?.attention ? "attention" : status?.running ? "running" : "");
    el("statusText").textContent = state.error ? i18n.loadingFailed : status?.error ? i18n.sessionError : status?.attention ? i18n.waitingForAction : status?.running ? i18n.running : i18n.idle;
    const search = el("search");
    if (document.activeElement !== search) search.value = state.query || "";
    el("counts").textContent = `${state.filteredRows || 0} ${i18n.rows} / ${state.totalRows || 0} ${i18n.projected} / ${state.totalEvents || 0} ${i18n.raw}` + (state.needsHistoryBaseline ? ` \xB7 ${i18n.historySyncing}` : "") + (state.followLatest ? ` \xB7 ${i18n.followLive}` : "");
    el("older").disabled = !state.hasOlder;
    el("newer").disabled = !state.hasNewer;
    el("latest").disabled = Boolean(state.followLatest);
    renderOverview();
    const projections = state.projections ?? [];
    el("projections").innerHTML = projections.length ? projections.map(
      (item) => `<div class="projection${state.selectedId === item.id ? " selected" : ""}" data-projection-key="${escapeHtml(item.key)}"><div class="projection-head"><span class="projection-key">${escapeHtml(item.key)}</span><span class="seq">seq ${escapeHtml(String(item.seq))}</span></div><div class="projection-value">${item.valueHtml}</div></div>`
    ).join("") : `<div class="empty">${escapeHtml(i18n.noProjections)}</div>`;
    const rows = state.rows ?? [];
    const ledger = el("ledger");
    if (state.error) {
      ledger.innerHTML = `<div class="error-box">${escapeHtml(state.error)}</div>`;
    } else if (rows.length === 0) {
      ledger.innerHTML = `<div class="empty">${escapeHtml(i18n.noRows)}</div>`;
    } else {
      ledger.innerHTML = rows.map((row, index) => rowMarkup(row, index, rows)).join("");
    }
    renderDetail();
  }
  function renderDetail() {
    document.querySelector(".inspector")?.classList.toggle("visible", Boolean(detail));
    el("detailKind").textContent = detail ? detail.kind : "Inspector";
    el("detailTitle").textContent = detail ? detail.title : i18n.selectRecord;
    const summary = el("summaryDetail");
    summary.innerHTML = detail ? (detail.summary ?? []).map(
      (field) => `<div class="field"><div class="field-label">${escapeHtml(field.label)}</div><div class="field-value">${field.valueHtml}</div></div>`
    ).join("") : `<div class="empty">${escapeHtml(i18n.deferredDetail)}</div>`;
    const raw = el("rawDetail");
    raw.innerHTML = detail ? detail.rawHtml : "";
    summary.classList.toggle("hidden", activeTab !== "summary");
    raw.classList.toggle("hidden", activeTab !== "raw");
    for (const tab of document.querySelectorAll("[data-tab]")) {
      tab.classList.toggle("active", tab.dataset.tab === activeTab);
    }
  }
  function clearSelection() {
    detail = void 0;
    state.selectedId = void 0;
    vscode.postMessage({ type: "clearSelection" });
    render();
  }
  function selectOrClearRow(rowId) {
    if (state.selectedId === rowId) clearSelection();
    else vscode.postMessage({ type: "selectRow", rowId });
  }
  document.addEventListener("click", (event) => {
    if (!openFileLocation(event.target)) return;
    event.preventDefault();
    event.stopPropagation();
  }, true);
  document.addEventListener("keydown", (event) => {
    if (event.key !== "Enter" && event.key !== " ") return;
    if (!openFileLocation(event.target)) return;
    event.preventDefault();
    event.stopPropagation();
  }, true);
  el("ledger").addEventListener("click", (event) => {
    const target = event.target instanceof Element ? event.target : void 0;
    const toggle = target?.closest("[data-toggle-row]");
    if (toggle?.dataset.toggleRow) {
      const id = toggle.dataset.toggleRow;
      if (collapsed.has(id)) collapsed.delete(id);
      else collapsed.add(id);
      render();
      return;
    }
    const row = target?.closest("[data-row-id]");
    if (row?.dataset.rowId) selectOrClearRow(row.dataset.rowId);
  });
  el("timelineLanes").addEventListener("click", (event) => {
    const target = event.target instanceof Element ? event.target : void 0;
    const item = target?.closest("[data-timeline-id]");
    if (item?.dataset.timelineId) selectOrClearRow(item.dataset.timelineId);
  });
  el("projections").addEventListener("click", (event) => {
    const target = event.target instanceof Element ? event.target : void 0;
    const item = target?.closest("[data-projection-key]");
    if (!item?.dataset.projectionKey) return;
    if (state.selectedId && document.querySelector(".projection.selected") === item) {
      clearSelection();
    } else {
      vscode.postMessage({ type: "selectProjection", key: item.dataset.projectionKey });
    }
  });
  document.querySelector(".tabs")?.addEventListener("click", (event) => {
    const target = event.target instanceof Element ? event.target : void 0;
    const tab = target?.closest("[data-tab]");
    if (!tab?.dataset.tab) return;
    activeTab = tab.dataset.tab;
    renderDetail();
  });
  el("search").addEventListener("input", (event) => {
    clearTimeout(searchTimer);
    const query = event.target.value;
    searchTimer = setTimeout(() => vscode.postMessage({ type: "setQuery", query }), 120);
  });
  el("timelineMode").addEventListener("click", () => {
    vscode.postMessage({
      type: "setTimelineMode",
      mode: state.timelineMode === "duration" ? "sequence" : "duration"
    });
  });
  el("older").addEventListener("click", () => vscode.postMessage({ type: "page", direction: "older" }));
  el("newer").addEventListener("click", () => vscode.postMessage({ type: "page", direction: "newer" }));
  el("latest").addEventListener("click", () => vscode.postMessage({ type: "page", direction: "latest" }));
  window.addEventListener("message", (event) => {
    const message = event.data;
    if (typeof message !== "object" || message === null) return;
    const envelope = message;
    if (envelope.type === "state") {
      state = message.state;
      if (state.selectedId === void 0 || state.selectedId === null) detail = void 0;
      render();
    } else if (envelope.type === "detail") {
      detail = message.detail;
      renderDetail();
    }
  });
  render();
  vscode.postMessage({ type: "ready" });
})();
