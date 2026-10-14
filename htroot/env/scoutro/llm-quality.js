/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later.
 *
 * The quality check of the LLM selection (docs/SCOUTRO_LLM_QUALITY_CHECK.md, help/LLMSelection_p.md): choose a candidate,
 * see scope and estimated cost, start the check explicitly, read results and deviations, and switch the knowledge model
 * only after a confirmation. Choosing a candidate or running a check changes no setting; the switch is the matrix's own
 * knowledge checkbox and save. Visible texts come from #qualityLabels and the static page (translated with the page).
 */
(function () {
  "use strict";
  var API = "/scoutro/api/v1/kg/llm-quality";
  var KNOWLEDGE_COLUMN = 13; // PRODUCTION_MODEL_COLUMN_NAMES[13] of the matrix
  var POLL_MILLIS = 2000;
  var state = null;
  var priceSource = "entered";
  var planned = false;
  var polling = null;
  var results = {};

  function el(id) { return document.getElementById(id); }

  function label(code) {
    var node = document.querySelector('#qualityLabels [data-q="' + code + '"]');
    return node ? node.textContent.trim() : code;
  }

  function fill(text, values) {
    return text.replace(/%(\d+)/g, function (m, n) {
      var v = values[Number(n) - 1];
      return v === undefined || v === null ? "" : String(v);
    });
  }

  function show(id, visible) {
    var node = el(id);
    if (node) node.style.display = visible ? "" : "none";
  }

  function message(text, failed) {
    var node = el("qualityMessage");
    if (!node) return;
    node.textContent = text || "";
    node.className = failed ? "alert alert-danger" : "alert alert-info";
    node.style.display = text ? "" : "none";
  }

  async function api(method, path, body) {
    var options = { method: method, credentials: "same-origin", headers: { "Accept": "application/json" } };
    if (body !== undefined) {
      options.headers["Content-Type"] = "application/json";
      options.body = JSON.stringify(body);
    }
    var response = await fetch(API + (path || ""), options);
    var payload = null;
    try { payload = await response.json(); } catch (e) { payload = null; }
    if (!response.ok) {
      var error = payload && payload.error ? payload.error : {};
      var failure = new Error(error.message || ("HTTP " + response.status));
      failure.status = response.status;
      failure.code = error.code || "";
      throw failure;
    }
    return payload;
  }

  function selected() {
    var select = el("qualityCandidate");
    if (!state || !select || !select.value) return null;
    return (state.candidates || []).find(function (c) { return c.key === select.value; }) || null;
  }

  function active() {
    return state ? (state.candidates || []).find(function (c) { return c.activeForKnowledge; }) || null : null;
  }

  function candidateName(c) {
    return c ? c.model + " (" + c.service + ", " + c.endpoint + ")" : label("none");
  }

  function statusText(c) {
    if (!c) return label("unchecked");
    var text = label(c.status || "unchecked");
    if (c.stale) text += " – " + fill(label("stale"), [(c.staleReasons || []).join(", ")]);
    return text;
  }

  function running() {
    var s = state && state.run ? state.run.state : "idle";
    return s === "running" || s === "cancelling";
  }

  // ------------------------------------------------------------ candidates

  function renderCandidates() {
    var select = el("qualityCandidate");
    if (!select) return;
    var before = select.value;
    select.textContent = "";
    (state.candidates || []).forEach(function (c) {
      var option = document.createElement("option");
      option.value = c.key;
      option.textContent = c.model + " – " + c.service + " – " + c.endpoint + (c.activeForKnowledge ? " (" + label("active") + ")" : "");
      select.appendChild(option);
    });
    if (before && (state.candidates || []).some(function (c) { return c.key === before; })) select.value = before;
    if (!(state.candidates || []).length) message(label("no_candidates"), false);
    if (!state.graphRunning) message(label("kg_not_running"), false);
  }

  function renderSelected() {
    var c = selected();
    var status = el("qualityCandidateStatus");
    if (status) status.textContent = c ? statusText(c) : "";
    var activate = el("qualityActivateButton");
    if (activate) activate.disabled = !c || c.activeForKnowledge || running() || !productionRow(c);
    var start = el("qualityStartButton");
    if (start) start.disabled = !c || !planned || running() || !state.graphRunning || !priceReady();
    show("qualityCancelButton", running());
  }

  function priceReady() {
    var input = el("qualityPriceIn"), output = el("qualityPriceOut"), accept = el("qualityAcceptUnknown");
    var hasPrice = input && output && input.value.trim() !== "" && output.value.trim() !== "";
    return hasPrice || (accept && accept.checked);
  }

  /** The price of an OpenRouter-like model list (USD per token) for the candidate, through the administrator passthrough. */
  async function providerPrice(c) {
    priceSource = "entered";
    var source = el("qualityPriceSource");
    if (source) source.textContent = label("price_none");
    if (!c || c.service === "OLLAMA") return;
    try {
      var response = await fetch("/v1/models?hoststub=" + encodeURIComponent(c.key.split("|")[1]), { credentials: "same-origin" });
      if (!response.ok) return;
      var list = await response.json();
      var model = (list && Array.isArray(list.data) ? list.data : []).find(function (m) { return m && m.id === c.model; });
      var pricing = model && model.pricing;
      if (!pricing || pricing.prompt === undefined || pricing.completion === undefined) return;
      var perMillion = function (v) { var n = Number(v) * 1e6; return Number.isFinite(n) && n >= 0 ? String(Math.round(n * 1e6) / 1e6) : ""; };
      var input = perMillion(pricing.prompt), output = perMillion(pricing.completion);
      if (input === "" || output === "") return;
      el("qualityPriceIn").value = input;
      el("qualityPriceOut").value = output;
      el("qualityCurrency").value = "USD";
      priceSource = "provider_list";
      if (source) source.textContent = label("price_provider_list");
    } catch (e) {
      // no list: the price stays unknown until entered
    }
  }

  // ------------------------------------------------------------ plan

  function query() {
    var c = selected();
    var params = new URLSearchParams();
    params.set("candidate", c ? c.key : "");
    var input = el("qualityPriceIn").value.trim(), output = el("qualityPriceOut").value.trim();
    if (input !== "" || output !== "") {
      params.set("inputPrice", input);
      params.set("outputPrice", output);
      params.set("currency", el("qualityCurrency").value);
      params.set("priceSource", priceSource);
    }
    var calls = el("qualityMaxCalls").value.trim(), cost = el("qualityMaxCost").value.trim();
    if (calls !== "") params.set("maxCalls", calls);
    if (cost !== "") params.set("maxCost", cost);
    return params;
  }

  function cell(selector, text) {
    var node = document.querySelector(selector);
    if (node) node.textContent = text;
  }

  async function showPlan() {
    message("");
    try {
      var p = await api("GET", "/plan?" + query().toString());
      cell('[data-q-plan="pages"]', p.scope.cases + " (" + p.scope.testset + " " + p.scope.version + ")");
      cell('[data-q-plan="requests"]', String(p.scope.requests));
      cell('[data-q-plan="calls"]', p.calls.cap + " (" + p.calls.planned + " / " + p.calls.worstCase + ")");
      cell('[data-q-plan="tokens"]', String(p.tokens.worstCase));
      cell('[data-q-plan="cost"]', p.cost.known ? p.cost.worstCase + " " + p.cost.currency : label("cost_unknown_value"));
      cell('[data-q-plan="window"]', p.window.tokens + " (" + p.window.source + ")");
      if (el("qualityMaxCalls").value.trim() === "") el("qualityMaxCalls").value = p.calls.cap;
      if (p.cost.known && el("qualityMaxCost").value.trim() === "") el("qualityMaxCost").value = p.cost.worstCase;
      show("qualityPlan", true);
      planned = !!p.startable;
    } catch (e) {
      planned = false;
      message(e.message, true);
    }
    renderSelected();
  }

  // ------------------------------------------------------------ run

  async function start() {
    var c = selected();
    if (!c) return;
    var input = el("qualityPriceIn").value.trim(), output = el("qualityPriceOut").value.trim();
    var body = { action: "start", candidate: c.key, acceptUnknownCost: el("qualityAcceptUnknown").checked };
    if (input !== "" || output !== "") {
      body.price = { inputPerMillion: input, outputPerMillion: output, currency: el("qualityCurrency").value, source: priceSource };
    }
    var calls = el("qualityMaxCalls").value.trim(), cost = el("qualityMaxCost").value.trim();
    if (calls !== "") body.maxCalls = calls;
    if (cost !== "") body.maxCost = cost;
    message("");
    try {
      state = await api("POST", "", body);
      renderRun();
      renderSelected();
      poll();
    } catch (e) {
      message(e.message, true);
    }
  }

  async function cancel() {
    try {
      state = await api("POST", "", { action: "cancel" });
      renderRun();
    } catch (e) {
      message(e.message, true);
    }
  }

  function renderRun() {
    var run = state && state.run;
    var node = el("qualityRun");
    if (!node || !run) return;
    var text = "";
    if (run.state === "running") {
      var cost = run.cost && run.cost.known ? run.cost.spent + " / " + run.cost.cap + " " + run.cost.currency : label("cost_unknown_value");
      text = fill(label("running"), [run.caseIndex, run.cases, run.calls.made, run.calls.cap, cost]);
    } else if (run.state === "cancelling") {
      text = label("cancelling");
    } else if (run.state === "cancelled") {
      text = label("cancelled");
    } else if (run.state === "stopped") {
      text = fill(label("stopped"), [label(run.stopReason)]);
    }
    node.textContent = text;
    node.style.display = text ? "" : "none";
  }

  function poll() {
    if (polling) clearTimeout(polling);
    polling = setTimeout(async function () {
      polling = null;
      await refresh();
    }, POLL_MILLIS);
  }

  // ------------------------------------------------------------ result

  function td(text) {
    var cellNode = document.createElement("td");
    cellNode.textContent = text || "";
    return cellNode;
  }

  function statement(part) {
    if (!part) return "\u2014";
    var text = part.statement || "";
    if (part.evidence) text += " – “" + part.evidence + "”";
    if (part.reason) text += " (" + part.reason + ")";
    return text;
  }

  function renderResult(r) {
    if (!r || r.status === "unchecked") {
      show("qualityResult", false);
      return;
    }
    show("qualityResult", true);
    el("qualityResultStatus").textContent = label(r.status);
    var s = r.scope || {};
    el("qualityResultScope").textContent = fill(label("scope"), [s.casesEvaluated, s.cases, s.testset, s.version, s.expectedOfModel,
      s.givenByRules, s.forbiddenChecked]) + (r.stopReason ? " " + fill(label("stopped"), [label(r.stopReason)]) : "");
    var stale = el("qualityResultStale");
    stale.textContent = r.stale ? fill(label("stale"), [(r.staleReasons || []).join(", ")]) : "";
    stale.style.display = r.stale ? "" : "none";
    var fn = r.functional || {};
    ["correct", "missing", "forbidden", "wrongType", "wrongRelation", "evidence", "allowed", "notRated"].forEach(function (k) {
      cell('[data-q-fn="' + k + '"]', String(fn[k] || 0));
    });
    cell('[data-q-fn="givenByRules"]', String(s.givenByRules || 0));
    var t = r.technical || {};
    cell('[data-q-tech="apiErrors"]', String(t.apiErrors || 0));
    cell('[data-q-tech="refusedAnswers"]', String(t.refusedAnswers || 0));
    cell('[data-q-tech="validatorDrops"]', String(t.validatorDrops || 0));
    cell('[data-q-tech="truncated"]', String(t.truncated || 0));
    cell('[data-q-tech="calls"]', (t.calls || 0) + " / " + (t.callCap || 0) + ", " + (t.retries || 0));
    cell('[data-q-tech="duration"]', ((t.durationMillis || 0) / 1000).toFixed(1) + " s");
    var tok = t.tokens || {};
    cell('[data-q-tech="tokens"]', (tok.prompt || 0) + " / " + (tok.answer || 0) + " / " + (tok.estimated || 0));
    var cost = t.cost || {};
    cell('[data-q-tech="cost"]', cost.known ? cost.amount + " " + cost.currency + (cost.estimated ? " (~)" : "") : label("cost_unknown_value"));
    // deviations: each with the reference page, its kind, the expectation, the actual statement and the page's passage
    var body = el("qualityDeviationsTable").tBodies[0];
    body.textContent = "";
    var count = 0;
    (fn.cases || []).forEach(function (c) {
      (c.deviations || []).forEach(function (d) {
        var row = document.createElement("tr");
        row.appendChild(td(label("case_" + c["case"])));
        var kind = label(d.kind);
        if (d.where && d.where.why) kind += " – " + label(d.where.why) + (d.where.reasons && d.where.reasons.length ? " (" + d.where.reasons.join(", ") + ")" : "");
        row.appendChild(td(kind));
        row.appendChild(td(statement(d.expected || d.forbidden)));
        row.appendChild(td(statement(d.actual)));
        row.appendChild(td(d.source || ""));
        body.appendChild(row);
        count++;
      });
    });
    (t.cases || []).forEach(function (c) {
      if (!c.notAsked && !c.error) return;
      var row = document.createElement("tr");
      row.appendChild(td(label("case_" + c["case"])));
      row.appendChild(td(label("technical_failure")));
      row.appendChild(td(""));
      row.appendChild(td(c.error ? c.error.message : label(c.notAsked)));
      row.appendChild(td(""));
      body.appendChild(row);
      count++;
    });
    show("qualityNoDeviations", count === 0);
    // accepted statements the test set does not rate: listed, so that no one reads the result as a plain pass
    var unrated = el("qualityUnratedTable").tBodies[0];
    unrated.textContent = "";
    var listed = 0;
    (fn.cases || []).forEach(function (c) {
      var items = c.notRated || [];
      items.forEach(function (item) {
        var row = document.createElement("tr");
        row.appendChild(td(label("case_" + c["case"])));
        row.appendChild(td(item.statement));
        row.appendChild(td(item.evidence ? "“" + item.evidence + "”" : ""));
        unrated.appendChild(row);
        listed++;
      });
      var more = ((c.counts || {}).notRated || 0) - items.length;
      if (more > 0) {
        var row = document.createElement("tr");
        row.appendChild(td(label("case_" + c["case"])));
        row.appendChild(td(fill(label("more_unrated"), [more])));
        row.appendChild(td(""));
        unrated.appendChild(row);
        listed++;
      }
    });
    show("qualityUnrated", listed > 0);
    var p = r.provenance || {};
    var cand = p.candidate || {};
    cell('[data-q-prov="model"]', cand.service + " / " + cand.model + " – " + cand.endpoint);
    var served = p.served || {};
    cell('[data-q-prov="served"]', Object.keys(served.models || {}).join(", ") + (Object.keys(served.providers || {}).length
      ? " – " + Object.keys(served.providers).join(", ") : "") + (served.unreportedCalls ? " (" + served.unreportedCalls + " ?)" : ""));
    var par = p.parameters || {};
    cell('[data-q-prov="parameters"]', "max_tokens " + par.maxTokens + ", window " + (par.window ? par.window.tokens + " (" + par.window.source + ")" : "?")
      + ", temperature " + par.temperature + ", structured output " + par.structuredOutput);
    var pr = p.prompt || {};
    cell('[data-q-prov="prompt"]', pr.source + " #" + pr.revision + " (" + pr.hash + ")");
    cell('[data-q-prov="versions"]', p.schema + " – " + p.validator + " – " + p.rules);
    cell('[data-q-prov="testset"]', p.testset + " – evaluator " + p.evaluator);
    cell('[data-q-prov="checked"]', r.finishedAt ? new Date(r.finishedAt).toLocaleString() : "");
  }

  async function loadResult() {
    var c = selected();
    if (!c) {
      renderResult(null);
      return;
    }
    if (c.status === "unchecked") {
      renderResult(null);
      return;
    }
    try {
      var r = await api("GET", "/result?candidate=" + encodeURIComponent(c.key));
      results[c.key] = r;
      renderResult(r);
    } catch (e) {
      message(e.message, true);
    }
  }

  // ------------------------------------------------------------ the switch: only confirmed

  function productionRow(c) {
    var table = document.getElementById("productionModelsTable");
    if (!table || !c) return null;
    return Array.from(table.tBodies[0].rows).find(function (row) {
      var cells = row.cells;
      return cells.length > 2 && cells[0].textContent.trim() === c.service && cells[1].textContent.trim() === c.model
        && cells[2].textContent.trim().replace(/\/+$/, "") === c.key.split("|")[1];
    }) || null;
  }

  function rowCandidate(row) {
    if (!state || !row) return null;
    return (state.candidates || []).find(function (c) { return productionRow(c) === row; }) || null;
  }

  function confirmSwitch(c) {
    var current = active();
    return window.confirm(fill(label("confirmSwitch"), [candidateName(c), current ? candidateName(current) : label("none"), statusText(c)]));
  }

  function activate() {
    var c = selected();
    var row = productionRow(c);
    if (!c || !row || !confirmSwitch(c)) return;
    var box = row.cells[KNOWLEDGE_COLUMN] && row.cells[KNOWLEDGE_COLUMN].querySelector('input[type="checkbox"]');
    if (!box) return;
    box.checked = true;
    // the matrix's own exclusive switch and save (persistProductionModels): one save path
    if (typeof handleUsageCheckboxToggle === "function") handleUsageCheckboxToggle(KNOWLEDGE_COLUMN, box);
    setTimeout(refresh, 500);
  }

  /** The knowledge checkbox and the undeploy of the knowledge row change the model only after a confirmation. */
  function guardMatrix() {
    var table = document.getElementById("productionModelsTable");
    if (!table || table.dataset.qualityGuard === "1") return;
    table.dataset.qualityGuard = "1";
    table.addEventListener("change", function (event) {
      var box = event.target;
      if (!box || box.type !== "checkbox" || box.dataset.featureColumn !== String(KNOWLEDGE_COLUMN)) return;
      var row = box.closest("tr");
      var c = rowCandidate(row);
      var name = c ? candidateName(c) : (row && row.cells[1] ? row.cells[1].textContent.trim() : "");
      var ok = box.checked ? window.confirm(fill(label("confirmSwitch"), [name, candidateName(active()), statusText(c)]))
        : window.confirm(fill(label("confirmOff"), [name]));
      if (!ok) {
        box.checked = !box.checked; // the active configuration stays as it is
        event.stopImmediatePropagation();
        event.stopPropagation();
      } else {
        setTimeout(refresh, 500);
      }
    }, true);
    table.addEventListener("click", function (event) {
      var button = event.target && event.target.closest ? event.target.closest('button[data-action="undeploy"]') : null;
      if (!button) return;
      var row = button.closest("tr");
      var box = row && row.cells[KNOWLEDGE_COLUMN] && row.cells[KNOWLEDGE_COLUMN].querySelector('input[type="checkbox"]');
      if (!box || !box.checked) return;
      if (!window.confirm(fill(label("confirmOff"), [row.cells[1].textContent.trim()]))) {
        event.stopImmediatePropagation();
        event.stopPropagation();
        event.preventDefault();
      }
    }, true);
  }

  // ------------------------------------------------------------ refresh

  async function refresh() {
    try {
      state = await api("GET", "");
    } catch (e) {
      message(e.message, true);
      return;
    }
    renderCandidates();
    renderRun();
    renderSelected();
    if (running()) {
      poll();
    } else {
      await loadResult();
    }
  }

  function init() {
    if (!el("qualityCheckContainer")) return;
    guardMatrix();
    el("qualityCandidate").addEventListener("change", function () {
      planned = false;
      show("qualityPlan", false);
      el("qualityPriceIn").value = "";
      el("qualityPriceOut").value = "";
      el("qualityMaxCalls").value = "";
      el("qualityMaxCost").value = "";
      renderSelected();
      loadResult();
      providerPrice(selected());
    });
    ["qualityPriceIn", "qualityPriceOut", "qualityCurrency"].forEach(function (id) {
      el(id).addEventListener("input", function () {
        priceSource = "entered";
        planned = false;
        el("qualityMaxCost").value = "";
        cell("#qualityPriceSource", label("price_entered"));
        renderSelected();
      });
    });
    ["qualityMaxCalls", "qualityMaxCost", "qualityAcceptUnknown"].forEach(function (id) {
      el(id).addEventListener("change", function () {
        planned = false;
        renderSelected();
      });
    });
    el("qualityPlanButton").addEventListener("click", showPlan);
    el("qualityStartButton").addEventListener("click", start);
    el("qualityCancelButton").addEventListener("click", cancel);
    el("qualityActivateButton").addEventListener("click", activate);
    refresh().then(function () { providerPrice(selected()); });
  }

  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", init);
  else init();
})();
