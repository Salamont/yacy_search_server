/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later.
 * The "thinking" capability of an OLLAMA model, measured natively: can this model think on Ollama's /api/chat?
 * Ollama itself decides that from the model's capability list: a model with "thinking" thinks by default on /api/chat
 * unless the request says think: false (then its answer budget can be spent on thinking alone), and think: true is
 * refused (400) for a model without it, while think: false is accepted for every model.
 *   1. POST /api/show {model}: its "capabilities" list is that same list, static and without generating anything:
 *      "thinking" listed -> supported, else unsupported.
 *   2. Only if /api/show gives no capability list (an Ollama before the list existed, or a model whose public list
 *      hides it): a small native /api/chat with think: true and a short budget; thinking in the answer -> supported,
 *      an answer without thinking -> unsupported, a 400 while the same request without think is answered -> unsupported.
 *   Anything else (network, timeout, 401/403/404/429, 5xx) -> unknown: not stored, the next visit asks again.
 * The result of an OLLAMA model is stored with thinking_probe = VERSION_OLLAMA. A value without it was measured by the
 * former streaming test on /v1/chat/completions (which misses the thinking of e.g. a Qwen3 model) and counts as unknown
 * for OLLAMA. Every other service keeps that former test and its stored values unchanged (no version). */
(function (root) {
  'use strict';
  const VERSION_OLLAMA = 2;
  const USER = 'Hello';
  const RESULTS = ['supported', 'unsupported'];

  const native = service => String(service || '').trim() === 'OLLAMA';
  /** The probe version whose thinking result counts for a service: 0 (any stored value) except for OLLAMA. */
  function version(service) { return native(service) ? VERSION_OLLAMA : 0; }

  /** The /api/show request of the native probe. */
  function showPayload(model) { return { model }; }

  /** supported or unsupported from an /api/show answer with a capability list; null without one (the chat test decides). */
  function classifyShow(status, body) {
    if (status !== 200 || !body || typeof body !== 'object' || !Array.isArray(body.capabilities)) return null;
    const capabilities = body.capabilities.map(c => String(c).trim().toLowerCase());
    if (capabilities.includes('thinking')) return 'supported';
    // a "decision" model shows a reduced public list, which says nothing about thinking
    if (capabilities.length === 0 || capabilities.includes('decision')) return null;
    return 'unsupported';
  }

  /** The native chat test (think: true, a short budget) or, with control, the same request without think. */
  function chatPayload(model, control) {
    const body = { model, messages: [{ role: 'user', content: USER }], stream: false, options: { temperature: 0, num_predict: 64 } };
    if (!control) body.think = true;
    return body;
  }

  /** The result of the chat test: the status with think, its parsed body (200) and the status of the control (after a 400). */
  function classifyChat(probe) {
    const status = probe && typeof probe.status === 'number' ? probe.status : 0;
    if (status === 200) {
      const message = probe.body && probe.body.message && typeof probe.body.message === 'object' ? probe.body.message : null;
      if (!message) return 'unknown';
      return typeof message.thinking === 'string' && message.thinking.trim() !== '' ? 'supported' : 'unsupported';
    }
    if (status === 400) return probe.controlStatus === 200 ? 'unsupported' : 'unknown';
    return 'unknown';
  }

  /** The stored thinking capability of an entry of ai.model_capabilities: for OLLAMA only a result of the native probe counts. */
  function stored(entry, service) {
    if (!entry || typeof entry !== 'object') return 'unknown';
    if (native(service) && Number(entry.thinking_probe) !== VERSION_OLLAMA) return 'unknown';
    const value = typeof entry.thinking === 'string' ? entry.thinking.trim().toLowerCase() : '';
    return RESULTS.includes(value) ? value : 'unknown';
  }

  /**
   * Whether a native Ollama request of the knowledge tier or the format probe says think: false: always, except for a
   * model known not to think. Ollama accepts think: false for every model; it is left out only where it is known to be moot.
   */
  function noThinking(status) { return status !== 'unsupported'; }

  const api = { VERSION_OLLAMA, USER, native, version, showPayload, classifyShow, chatPayload, classifyChat, stored, noThinking };
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.ScoutroThinkingProbe = api;
})(typeof window !== 'undefined' ? window : this);
