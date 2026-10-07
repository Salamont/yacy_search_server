#!/usr/bin/env node
/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later.
 * Offline unit test of the LLM selection's "format" probe (htroot/env/scoutro/format-probe.js): a technical
 * structured-output test, a small state machine of results, and old (mood probe) values read as unknown.
 * Run: node test/scoutro-ui/format-probe-test.mjs (no browser, no network). */
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const probe = require('../../htroot/env/scoutro/format-probe.js');
let checks = 0;
function check(condition, message) { assert(condition, message); checks++; }
const ok = content => ({ status: 200, body: { choices: [{ message: { content }, finish_reason: 'stop' }] } });

// the schema: valid JSON Schema (only JSON types), closed, one required constant; the prompt does not give the answer
const JSON_TYPES = ['null', 'boolean', 'object', 'array', 'number', 'string', 'integer'];
const types = [];
(function walk(s) { if (s && typeof s === 'object') { if ('type' in s) types.push(s.type); Object.values(s).forEach(walk); } })(probe.SCHEMA);
check(types.length === 2 && types.every(t => JSON_TYPES.includes(t)), 'only JSON Schema types: ' + types);
check(probe.SCHEMA.additionalProperties === false && JSON.stringify(probe.SCHEMA.required) === '["result"]'
  && JSON.stringify(probe.SCHEMA.properties.result.enum) === '["ok"]', 'closed schema with one constant');
check(!probe.USER.includes('ok') && !probe.SYSTEM.includes('ok') && !/mood|angry|happy/i.test(probe.SYSTEM + probe.USER), 'no task, no answer in the prompt');
try {
  const Ajv = require('ajv');
  new Ajv({ strict: true }).compile(probe.SCHEMA);
  check(true, 'ajv compiles the schema');
} catch (e) {
  if (e.code !== 'MODULE_NOT_FOUND') throw e; // ajv is optional here
}

// A) the request: the OpenAI json_schema contract; the control request is the same without it
const p = probe.payload('llama3.1:8b', false);
check(p.response_format.type === 'json_schema' && p.response_format.json_schema.name === 'scoutro_format_probe'
  && p.response_format.json_schema.strict === true && p.response_format.json_schema.schema === probe.SCHEMA, 'json_schema request');
check(p.model === 'llama3.1:8b' && p.stream === false && p.temperature === 0 && p.messages.length === 2, 'request fields');
const c = probe.payload('llama3.1:8b', true);
check(!('response_format' in c) && JSON.stringify({ ...p, response_format: undefined }) === JSON.stringify(c), 'control without the schema, otherwise equal');

// A) compliant answer -> supported (content text, parsed object, an object content, a closed think block)
check(probe.classify(ok('{"result":"ok"}')) === 'supported', 'compliant: supported');
check(probe.classify(ok(' {\n "result" : "ok" }\n')) === 'supported', 'whitespace only: supported');
check(probe.classify({ status: 200, body: { choices: [{ message: { content: '', parsed: { result: 'ok' } } }] } }) === 'supported', 'parsed: supported');
check(probe.classify({ status: 200, body: { choices: [{ message: { content: { result: 'ok' } } }] } }) === 'supported', 'object content: supported');
check(probe.classify(ok('<think>x</think>{"result":"ok"}')) === 'supported', 'think block removed: supported');

// B) the endpoint refuses the schema (400/422) and answers the same request without it -> unsupported
check(probe.classify({ status: 400, controlStatus: 200 }) === 'unsupported', '400 + control 200: unsupported');
check(probe.classify({ status: 422, controlStatus: 200 }) === 'unsupported', '422 + control 200: unsupported');

// C) the schema is accepted (200) but not followed -> ignored (never supported, never unsupported)
for (const [content, what] of [['{"result":"ok","note":"x"}', 'extra field'], ['{"result":"OK"}', 'other value'], ['{}', 'missing field'],
  ['Sure! {"result":"ok"}', 'prose'], ['```json\n{"result":"ok"}\n```', 'code fence'], ['["ok"]', 'array'], ['', 'empty'],
  ['{"result":"ok"', 'truncated']]) {
  check(probe.classify(ok(content)) === 'ignored', what + ': ignored');
}
check(probe.classify({ status: 200, body: null }) === 'ignored', 'no JSON body: ignored');
check(probe.classify({ status: 200, body: { choices: [] } }) === 'ignored', 'no choice: ignored');

// D) no clear technical answer -> unknown (not stored): network, timeout, auth, missing endpoint/model, rate limit, 5xx,
// and a 400 whose control request fails too (the request itself is wrong, not the schema)
for (const status of [0, 401, 403, 404, 405, 408, 415, 429, 500, 502, 503, 504]) {
  check(probe.classify({ status }) === 'unknown', status + ': unknown');
}
check(probe.classify({ status: 400 }) === 'unknown', '400 without control: unknown');
check(probe.classify({ status: 400, controlStatus: 400 }) === 'unknown', '400 + control 400: unknown');
check(probe.classify({ status: 422, controlStatus: 503 }) === 'unknown', '422 + control 503: unknown');
check(probe.classify(undefined) === 'unknown' && probe.classify({}) === 'unknown', 'nothing: unknown');

// E) stored values: only a result of this probe version counts; the old mood probe's values are unknown
check(probe.VERSION === 2, 'probe version 2');
check(probe.stored({ thinking: 'supported', tooling: 'supported', vision: 'unsupported', format: 'unsupported' }) === 'unknown', 'legacy unsupported: unknown');
check(probe.stored({ format: 'supported' }) === 'unknown', 'legacy supported: unknown');
check(probe.stored({ format: 'unsupported', format_probe: 1 }) === 'unknown', 'older version: unknown');
check(probe.stored({ format: 'unsupported', format_probe: 2 }) === 'unsupported', 'current unsupported');
check(probe.stored({ format: 'supported', format_probe: 2 }) === 'supported', 'current supported');
check(probe.stored({ format: 'ignored', format_probe: '2' }) === 'ignored', 'current ignored (version as text)');
check(probe.stored({ format: 'unknown', format_probe: 2 }) === 'unknown' && probe.stored({ format: 'yes', format_probe: 2 }) === 'unknown', 'other values: unknown');
check(probe.stored(null) === 'unknown' && probe.stored(undefined) === 'unknown', 'no entry: unknown');

console.log(`PASS: ${checks} format probe checks`);
