/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later.
 * The "format" capability probe of the LLM selection: does this endpoint and model accept and follow a JSON schema
 * (OpenAI response_format json_schema)? A technical test only, with no task the model could answer wrongly: the prompt
 * does not say what to answer, so only an enforced schema yields exactly {"result":"ok"}.
 * An OLLAMA model is tested on its native /api/chat with format (VERSION_OLLAMA), the path the knowledge graph uses for
 * it; every other service on the OpenAI-compatible /v1/chat/completions with response_format json_schema (VERSION).
 * Results (stored with format_probe = the service's version in ai.model_capabilities):
 *   supported   - HTTP 200 and exactly the schema's object;
 *   unsupported - HTTP 400 or 422 with the schema, while the same request without it is answered (200);
 *   ignored     - HTTP 200, but the answer is no JSON object of the schema (the parameter is accepted, not followed);
 *   unknown     - anything else (network, timeout, 401/403/404/405/429, 5xx, a control request that fails too): not stored.
 * A format value without the service's version counts as unknown: the mood probe up to Scoutro 0.8.3, and for OLLAMA
 * also a version 2 value (measured on /v1/chat/completions, which says nothing about /api/chat) and a version 3 value
 * (sent without think: false to a thinking model whose answer budget could go to thinking alone, see thinking-probe.js). */
(function (root) {
  'use strict';
  const VERSION = 2;
  const VERSION_OLLAMA = 4;
  const SCHEMA_NAME = 'scoutro_format_probe';
  const SCHEMA = {
    type: 'object',
    properties: { result: { type: 'string', enum: ['ok'] } },
    required: ['result'],
    additionalProperties: false
  };
  const SYSTEM = 'You are a test endpoint.';
  const USER = 'Return the required JSON object.';
  const RESULTS = ['supported', 'unsupported', 'ignored'];

  const native = service => String(service || '').trim() === 'OLLAMA';
  /** The probe version whose result counts for a service. */
  function version(service) { return native(service) ? VERSION_OLLAMA : VERSION; }
  /** The chat path of the probe: Ollama's native /api/chat, else the OpenAI-compatible one. */
  function path(service) { return native(service) ? '/api/chat' : '/v1/chat/completions'; }

  /**
   * The probe request (with the schema) or, with control, the same request without it: for OLLAMA natively
   * (format, options, think false unless the model is known not to think), else OpenAI-compatible (response_format json_schema).
   */
  function payload(model, control, service, noThinking) {
    if (native(service)) {
      const body = {
        model,
        messages: [{ role: 'system', content: SYSTEM }, { role: 'user', content: USER }],
        stream: false,
        options: { temperature: 0, num_predict: 64 }
      };
      if (noThinking) body.think = false;
      if (!control) body.format = SCHEMA;
      return body;
    }
    const body = {
      model,
      temperature: 0,
      max_tokens: 64,
      messages: [{ role: 'system', content: SYSTEM }, { role: 'user', content: USER }],
      stream: false
    };
    if (!control) body.response_format = { type: 'json_schema', json_schema: { name: SCHEMA_NAME, strict: true, schema: SCHEMA } };
    return body;
  }

  /**
   * The answer of a response as a value (choices[0].message of /v1/chat/completions, message of Ollama's /api/chat):
   * message.parsed, an object content, or the text parsed as JSON.
   */
  function answer(response) {
    const message = response && Array.isArray(response.choices) && response.choices[0] ? response.choices[0].message
      : response && response.message && typeof response.message === 'object' ? response.message : null;
    if (!message) return undefined;
    if (message.parsed && typeof message.parsed === 'object') return message.parsed;
    if (message.content && typeof message.content === 'object' && !Array.isArray(message.content)) return message.content;
    if (typeof message.content !== 'string') return undefined;
    const text = message.content.replace(/<think>[\s\S]*?<\/think>/g, '').trim();
    try {
      return JSON.parse(text);
    } catch (e) {
      return undefined;
    }
  }

  /** Exactly the schema's object: {"result":"ok"} and nothing else. */
  function compliant(value) {
    return !!value && typeof value === 'object' && !Array.isArray(value)
      && Object.keys(value).length === 1 && value.result === 'ok';
  }

  /**
   * The result of one probe: the HTTP status of the request with the schema (0: no answer, e.g. network or timeout),
   * its parsed body (for 200) and the HTTP status of the control request without the schema (only asked after 400/422).
   */
  function classify(probe) {
    const status = probe && typeof probe.status === 'number' ? probe.status : 0;
    if (status === 200) return compliant(answer(probe.body)) ? 'supported' : 'ignored';
    if (status === 400 || status === 422) return probe.controlStatus === 200 ? 'unsupported' : 'unknown';
    return 'unknown';
  }

  /** The stored format capability of an entry of ai.model_capabilities: only a result of the service's probe version counts. */
  function stored(entry, service) {
    if (!entry || typeof entry !== 'object' || Number(entry.format_probe) !== version(service)) return 'unknown';
    const value = typeof entry.format === 'string' ? entry.format.trim().toLowerCase() : '';
    return RESULTS.includes(value) ? value : 'unknown';
  }

  const api = { VERSION, VERSION_OLLAMA, SCHEMA_NAME, SCHEMA, SYSTEM, USER, version, path, payload, answer, compliant, classify, stored };
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.ScoutroFormatProbe = api;
})(typeof window !== 'undefined' ? window : this);
