#!/usr/bin/env node
/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later.
 * Offline unit test of the native thinking probe of an OLLAMA model (htroot/env/scoutro/thinking-probe.js): Ollama's own
 * capability list of /api/show, else a small /api/chat with think: true; old (/v1) values of an OLLAMA model read as unknown.
 * Run: node test/scoutro-ui/thinking-probe-test.mjs (no browser, no network). */
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const probe = require('../../htroot/env/scoutro/thinking-probe.js');
const format = require('../../htroot/env/scoutro/format-probe.js');
let checks = 0;
function check(condition, message) { assert(condition, message); checks++; }

// versions: OLLAMA only; every other service keeps its stored values as they are
check(probe.VERSION_OLLAMA === 2 && probe.version('OLLAMA') === 2 && probe.native(' OLLAMA '), 'OLLAMA: native probe version 2');
for (const service of ['OPENAI', 'OPENROUTER', 'LMSTUDIO', 'OTHER', '', undefined]) {
  check(probe.version(service) === 0 && !probe.native(service), String(service) + ': no native thinking probe');
}

// A) /api/show of a thinking model -> supported (whatever its name)
const show = capabilities => ({ license: '', template: '', details: {}, model_info: {}, capabilities });
check(JSON.stringify(probe.showPayload('qwen3:14b')) === '{"model":"qwen3:14b"}', '/api/show request: the model only');
check(probe.classifyShow(200, show(['completion', 'tools', 'thinking'])) === 'supported', 'show with thinking: supported');
check(probe.classifyShow(200, show(['Completion', ' THINKING '])) === 'supported', 'show: case and spaces');
// B) a model without thinking -> unsupported
check(probe.classifyShow(200, show(['completion', 'tools'])) === 'unsupported', 'show without thinking: unsupported');
check(probe.classifyShow(200, show(['completion', 'vision'])) === 'unsupported', 'show (vision) without thinking: unsupported');
// no capability list (an older Ollama, a reduced public list) or no answer: the chat test decides / unknown
check(probe.classifyShow(200, { license: '' }) === null && probe.classifyShow(200, show([])) === null
  && probe.classifyShow(200, show(['decision'])) === null && probe.classifyShow(200, null) === null, 'show without a usable list: chat test');
for (const status of [0, 401, 403, 404, 500, 503]) check(probe.classifyShow(status, show(['thinking'])) === null, 'show ' + status + ': no result');

// the chat fallback: think true, a short budget; the control the same without think
const c = probe.chatPayload('m', false);
check(c.think === true && c.stream === false && c.options.num_predict === 64 && c.options.temperature === 0 && c.messages.length === 1
  && !('format' in c) && !('reasoning_effort' in c), 'chat test: think true, short budget, native fields only');
const cc = probe.chatPayload('m', true);
check(!('think' in cc) && JSON.stringify({ ...c, think: undefined }) === JSON.stringify(cc), 'chat control: the same without think');
const chat = (message, done_reason = 'stop') => ({ status: 200, body: { model: 'm', message, done: true, done_reason } });
check(probe.classifyChat(chat({ role: 'assistant', content: '', thinking: 'The user greets me.' }, 'length')) === 'supported', 'chat: thinking in the answer: supported');
check(probe.classifyChat(chat({ role: 'assistant', content: 'Hi!' })) === 'unsupported', 'chat: no thinking: unsupported');
check(probe.classifyChat(chat({ role: 'assistant', content: 'Hi!', thinking: '  ' })) === 'unsupported', 'chat: blank thinking: unsupported');
check(probe.classifyChat({ status: 400, controlStatus: 200 }) === 'unsupported', 'chat: think refused, control answered: unsupported');
check(probe.classifyChat({ status: 400, controlStatus: 404 }) === 'unknown' && probe.classifyChat({ status: 400 }) === 'unknown', 'chat: 400 without a good control: unknown');
for (const status of [0, 401, 403, 404, 429, 500, 503]) check(probe.classifyChat({ status }) === 'unknown', 'chat ' + status + ': unknown');
check(probe.classifyChat({ status: 200, body: null }) === 'unknown' && probe.classifyChat(undefined) === 'unknown', 'chat: no body: unknown');

// C) stored values: an OLLAMA value counts only with thinking_probe 2; a /v1 value (none) is unknown, other services unchanged
check(probe.stored({ thinking: 'unsupported' }, 'OLLAMA') === 'unknown', 'Ollama legacy unsupported (the Qwen3 case): unknown');
check(probe.stored({ thinking: 'supported' }, 'OLLAMA') === 'unknown', 'Ollama legacy supported: unknown');
check(probe.stored({ thinking: 'supported', thinking_probe: 2 }, 'OLLAMA') === 'supported'
  && probe.stored({ thinking: 'unsupported', thinking_probe: '2' }, 'OLLAMA') === 'unsupported', 'Ollama v2: counts');
check(probe.stored({ thinking: 'supported', thinking_probe: 1 }, 'OLLAMA') === 'unknown'
  && probe.stored({ thinking: 'yes', thinking_probe: 2 }, 'OLLAMA') === 'unknown' && probe.stored(null, 'OLLAMA') === 'unknown', 'Ollama other: unknown');
for (const service of ['OPENAI', 'OPENROUTER', 'LMSTUDIO', 'OTHER']) {
  check(probe.stored({ thinking: 'supported' }, service) === 'supported' && probe.stored({ thinking: 'unsupported' }, service) === 'unsupported',
    service + ': stored values count as before');
}

// G) think: false for the native requests unless the model is known not to think
check(probe.noThinking('supported') && probe.noThinking('unknown') && !probe.noThinking('unsupported'), 'think false unless unsupported');
// D) a Qwen-like model: legacy unsupported -> unknown -> the format probe says think: false; after the native probe the same
const qwenLegacy = probe.stored({ thinking: 'unsupported' }, 'OLLAMA');
check(format.payload('qwen3:14b', false, 'OLLAMA', probe.noThinking(qwenLegacy)).think === false, 'format probe of a legacy Qwen3 value: think false');
const qwenNative = probe.stored({ thinking: probe.classifyShow(200, show(['completion', 'tools', 'thinking'])), thinking_probe: 2 }, 'OLLAMA');
check(qwenNative === 'supported' && format.payload('qwen3:14b', false, 'OLLAMA', probe.noThinking(qwenNative)).think === false, 'format probe after the native probe: think false');
// F) a model known not to think: the native format request without think, otherwise the same
const llama = probe.stored({ thinking: 'unsupported', thinking_probe: 2 }, 'OLLAMA');
const plain = format.payload('llama3.1:8b', false, 'OLLAMA', probe.noThinking(llama));
check(!('think' in plain) && plain.format === format.SCHEMA, 'non-thinking model: no think field');

// I) no model names: the same answers give the same result whatever the model is called
for (const name of ['qwen3:14b', 'llama3.1:8b', 'deepseek-r1', 'x']) {
  check(JSON.stringify(probe.showPayload(name)) === JSON.stringify({ model: name }) && probe.chatPayload(name, false).model === name, name + ': only the name is sent');
}
const source = require('node:fs').readFileSync(new URL('../../htroot/env/scoutro/thinking-probe.js', import.meta.url), 'utf8')
  .replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/.*$/gm, '');
check(!/\b(qwen|llama|deepseek|gemma|mistral|phi)/i.test(source), 'no model name in the probe code (OLLAMA aside)');

console.log(`PASS: ${checks} thinking probe checks`);
