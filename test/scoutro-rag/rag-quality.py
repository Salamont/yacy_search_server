#!/usr/bin/env python3
"""Reproducible RAG quality catalog on NEW disposable DATA only.

Seeds corpus.json into a disposable peer, sends every question of catalog.json through
the real chat endpoint (/v1/chat/completions, search=local, with the question's
collection) and records what the model receives. The model is a local recording
endpoint: a deterministic fake by default, or a recording proxy to a real
OpenAI-compatible endpoint when SCOUTRO_RAG_LLM (and SCOUTRO_RAG_MODEL) are set.

Usage: python3 test/scoutro-rag/rag-quality.py --label after --out results.json [--ui]
(--ui also drives the chat page with Playwright: needs NODE_PATH and SCOUTRO_CHROMIUM_PATH)
Measures per question: sources in the context, relevant (gold) sources, sources from
other collections, context and prompt size against num_ctx, UI sources equal to the
context sources, citation checks; and prompt growth over a multi-turn chat.
No production URL or DATA is accepted; no crawl is started. GPL-2.0-or-later.
"""
import argparse
import base64
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import re
import socket
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request

sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
JAVA = os.environ.get("JAVA", "java")
CLASSPATH = f"{REPO}/build/classes/java/main:{REPO}/lib/*"
NUM_CTX = int(os.environ.get("SCOUTRO_RAG_NUM_CTX", "4096"))
MAX_TOKENS = int(os.environ.get("SCOUTRO_RAG_MAX_TOKENS", "512"))
REAL_LLM = os.environ.get("SCOUTRO_RAG_LLM", "").rstrip("/")
REAL_MODEL = os.environ.get("SCOUTRO_RAG_MODEL", "llama3.1:8b")
CHARS_PER_TOKEN = 3.0  # conservative for German text with llama-style tokenizers
URL_PATTERN = re.compile(r"(?:Source|URL): (https?://\S+)")
INVENTED_URL = "https://erfunden.example/quelle"

recorded = []


class ModelEndpoint(BaseHTTPRequestHandler):
    """Records every chat request; answers deterministically or forwards to a real endpoint."""
    def log_message(self, *args):
        pass

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", "0"))) or b"{}")
        recorded.append(body)
        if REAL_LLM:
            forward = dict(body, model=REAL_MODEL)
            request = urllib.request.Request(REAL_LLM + "/v1/chat/completions", data=json.dumps(forward).encode(),
                                             headers={"Content-Type": "application/json"})
            with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(request, timeout=600) as response:
                data = response.read()
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
            return
        # deterministic answer that cites two sources, one invented number and one invented URL
        answer = "Laut den Quellen gibt es passende Angebote [1][2]. Weitere Details nennt [9], siehe " + INVENTED_URL + "."
        chunks = [{"choices": [{"delta": {"role": "assistant", "content": answer}}]},
                  {"choices": [{"delta": {}, "finish_reason": "stop"}]}]
        data = "".join("data: " + json.dumps(c) + "\n\n" for c in chunks).encode() + b"data: [DONE]\n\n"
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


def free_port():
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        return probe.getsockname()[1]


def message_text(content):
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        return "\n".join(part.get("text", "") for part in content if isinstance(part, dict) and part.get("type") == "text")
    return ""


def data_blocks(text):
    return re.findall(r"DATA-[0-9a-f]{32}-BEGIN\n(.*?)\nDATA-[0-9a-f]{32}-END", text, re.S)


def chat(base, messages, collection):
    body = {"model": "chat", "stream": True, "messages": messages}
    if collection:
        body["collection"] = collection
    request = urllib.request.Request(base + "/v1/chat/completions", data=json.dumps(body).encode(),
                                     headers={"Content-Type": "application/json"})
    before = len(recorded)
    with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(request, timeout=900) as response:
        stream = response.read().decode()
    meta, answer = {}, ""
    for line in stream.splitlines():
        if not line.startswith("data:") or "[DONE]" in line:
            continue
        try:
            event = json.loads(line[5:].strip())
        except ValueError:
            continue
        for key in ("search-filename", "search-text-base64", "scoutro-sources", "scoutro-citations", "scoutro-retrieval"):
            if key in event:
                meta[key] = event[key]
        for choice in event.get("choices", []):
            answer += (choice.get("delta") or {}).get("content") or ""
    return (recorded[before] if len(recorded) > before else None), meta, answer


def ui_urls(meta):
    if "scoutro-sources" in meta:
        return [s.get("url") for s in meta["scoutro-sources"]]
    if "search-text-base64" in meta:
        return URL_PATTERN.findall(base64.b64decode(meta["search-text-base64"]).decode("utf-8", "replace"))
    return []


def evaluate(question, sent, meta, answer, corpus):
    by_url = {d["url"]: d for d in corpus}
    messages = sent["messages"] if sent else []
    user = message_text(messages[-1]["content"]) if messages else ""
    blocks = data_blocks(user)
    context = "\n".join(blocks)
    urls = list(dict.fromkeys(URL_PATTERN.findall(context)))
    ids = [by_url[u]["id"] if u in by_url else u for u in urls]
    gold = set(question["gold"])
    relevant = [i for i in ids if i in gold]
    collection = question.get("collection")
    foreign = [i for i, u in zip(ids, urls) if collection and u in by_url and by_url[u]["collection"] != collection]
    prompt_chars = sum(len(message_text(m.get("content"))) for m in messages)
    tokens = int(prompt_chars / CHARS_PER_TOKEN)
    max_tokens = sent.get("max_tokens") if sent else None
    reserve = max_tokens if isinstance(max_tokens, int) else MAX_TOKENS
    shown = ui_urls(meta)
    citations = meta.get("scoutro-citations") or {}
    return {
        "id": question["id"], "kind": question["kind"], "collection": collection,
        "hits": len(urls), "relevant": len(relevant), "gold": len(gold),
        "precision": round(len(relevant) / len(urls), 2) if urls else None,
        "recall": round(len(relevant) / len(gold), 2) if gold else None,
        "foreign": foreign, "context_chars": len(context), "prompt_chars": prompt_chars, "prompt_tokens_est": tokens,
        "fits_num_ctx": tokens + reserve <= NUM_CTX,
        "ui_sources": len(shown), "ui_equals_context": shown == urls,
        "params": {k: sent.get(k) for k in ("temperature", "max_tokens", "num_ctx") if sent and k in sent},
        "query": (meta.get("scoutro-retrieval") or {}).get("query"),
        "stage": (meta.get("scoutro-retrieval") or {}).get("stage"),
        "citations": citations, "answer": answer[:600], "sources": ids,
    }


def conversation(base, convo):
    """Client behaviour of the existing chat page: the search document is attached to the
    user message and sent again in every later round."""
    messages, rows = [{"role": "system", "content": "You are a smart and helpful chatbot. If possible, use friendly emojies."}], []
    for turn, text in enumerate(convo["turns"], 1):
        user = {"role": "user", "content": text, "search": "local"}
        sent, meta, answer = chat(base, messages + [user], convo["collection"])
        prompt_chars = sum(len(message_text(m.get("content"))) for m in sent["messages"])
        rows.append({"turn": turn, "prompt_chars": prompt_chars, "prompt_tokens_est": int(prompt_chars / CHARS_PER_TOKEN),
                     "data_blocks": sum(len(data_blocks(message_text(m.get("content")))) for m in sent["messages"]),
                     "fits_num_ctx": prompt_chars / CHARS_PER_TOKEN + MAX_TOKENS <= NUM_CTX})
        attachment = None
        if "search-text-base64" in meta:
            attachment = {"type": "image_url", "image_url": {"url": "data:text/markdown;base64," + meta["search-text-base64"]},
                          "filename": meta.get("search-filename", "search.md")}
        user = {"role": "user", "content": [{"type": "text", "text": text}] + ([attachment] if attachment else [])}
        messages += [user, {"role": "assistant", "content": answer}]
    return {"id": convo["id"], "kind": convo["kind"], "turns": rows}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--label", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--ui", action="store_true", help="also run rag-chat-ui-test.mjs (Playwright) against the peer")
    args = parser.parse_args()
    corpus = json.loads((HERE / "corpus.json").read_text())["documents"]
    catalog = json.loads((HERE / "catalog.json").read_text())
    model = ThreadingHTTPServer(("127.0.0.1", 0), ModelEndpoint)
    threading.Thread(target=model.serve_forever, daemon=True).start()
    stub = f"http://127.0.0.1:{model.server_port}"
    port = free_port()
    base = f"http://127.0.0.1:{port}"
    process = None
    try:
        with tempfile.TemporaryDirectory(prefix="scoutro-rag-quality-") as temporary:
            root = Path(temporary)
            settings = root / "DATA/SETTINGS"
            settings.mkdir(parents=True)
            (root / ".scoutro-rag-disposable").touch()
            (root / "DATA/DICTIONARIES/harvesting").mkdir(parents=True)
            for friends in ("export_roar_ROAR_ListFriends.xml", "ListFriends.xml"):
                (root / "DATA/DICTIONARIES/harvesting" / friends).write_text('<?xml version="1.0" encoding="UTF-8"?>\n<BaseURLs/>\n')
            network = root / "fixture.network.unit"
            network.write_text((REPO / "defaults/yacy.network.webportal.unit").read_text().replace(
                "network.unit.domain = global", "network.unit.domain = any"))
            row = {"service": "OLLAMA", "model": "rag-fixture", "hoststub": stub, "api_key": "", "max_tokens": str(MAX_TOKENS),
                   "chat": True, "tldr": False, "logreport": False, "search": False, "translation": False,
                   "classification": False, "query": False, "qapairs": False, "thinking": False, "tooling": False,
                   "vision": False, "format": False}
            (settings / "yacy.conf").write_text("\n".join([
                f"port={port}", "adminAccountForLocalhost=false", "adminAccountAllPages=false",
                "adminAccountUserName=admin", "adminAccountBase64MD5=MD5:8cffbc0d66567a0987a4aba1ec46d63c",
                f"network.unit.definition={network}", "browserPopUpTrigger=false",
                "autocrawl=false", "server.https=false", "locale.language=default", "upnp.enabled=false", "donation.iframesource=",
                "scoutro.discovery.enabled=false", "search.verify=false",
                "search.ranking.solr.collection.filterquery.tmpa.0=httpstatus_i:200",
                "ai.production_models=" + json.dumps([row]), "ai.service_num_ctx=" + json.dumps({stub: NUM_CTX}),
                "resource.disk.free.min.steadystate=1", "resource.disk.free.min.undershot=1",
                "resource.disk.used.max.steadystate=1000000000000", "resource.disk.used.max.overshot=1000000000000",
            ]) + "\n")
            subprocess.run([JAVA, "-cp", CLASSPATH, str(HERE / "RagFixture.java"), str(root), str(HERE / "corpus.json")],
                           cwd=REPO, check=True, timeout=120, stdout=subprocess.DEVNULL)
            env = {k: v for k, v in os.environ.items() if k != "JAVA_TOOL_OPTIONS"}
            with (root / "peer.log").open("w") as log:
                process = subprocess.Popen([JAVA, "-Xmx1g", "-Djava.awt.headless=true", "-cp", CLASSPATH,
                                            "net.yacy.yacy", "-startup", str(root)], cwd=REPO, stdout=log, stderr=log, env=env)
                deadline = time.monotonic() + 90
                while True:
                    if process.poll() is not None:
                        raise RuntimeError((root / "peer.log").read_text()[-4000:])
                    try:
                        urllib.request.build_opener(urllib.request.ProxyHandler({})).open(base + "/api/version.xml", timeout=2)
                        break
                    except OSError:
                        if time.monotonic() > deadline:
                            raise RuntimeError((root / "peer.log").read_text()[-4000:])
                        time.sleep(0.5)
                # YaCy's search feed is asynchronous on its first request: warm it up
                for _ in range(20):
                    sent, meta, _ = chat(base, [{"role": "user", "content": "Pflegeheim", "search": "local"}], None)
                    if URL_PATTERN.search(message_text(sent["messages"][-1]["content"])):
                        break
                    time.sleep(0.5)
                hold = int(os.environ.get("SCOUTRO_RAG_HOLD", "0"))  # debugging: keep the peer for manual queries
                if hold:
                    print("peer", base, flush=True)
                    time.sleep(hold)
                results = []
                for question in catalog["questions"]:
                    messages = [{"role": "system", "content": "You are a smart and helpful chatbot. If possible, use friendly emojies."},
                                {"role": "user", "content": question["question"], "search": "local"}]
                    sent, meta, answer = chat(base, messages, question["collection"])
                    results.append(evaluate(question, sent, meta, answer, corpus))
                    # the existing behaviour without a collection field: whole index
                    if question["id"] in ("Q11-mischung", "Q13-mischung-coach"):
                        unscoped = dict(question, id=question["id"] + "-ohne-collection", collection=question["collection"])
                        sent, meta, answer = chat(base, messages, None)
                        results.append(dict(evaluate(unscoped, sent, meta, answer, corpus), request_collection=None))
                talk = conversation(base, catalog["conversation"])
                if args.ui:
                    subprocess.run(["node", str(HERE / "rag-chat-ui-test.mjs")], cwd=REPO, check=True, timeout=600,
                                   env=dict(env, SCOUTRO_URL=base))
                log_lines = [line for line in (root / "peer.log").read_text().splitlines() if "event=rag-" in line][-400:]
    finally:
        if process is not None and process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=60)
            except subprocess.TimeoutExpired:
                process.kill()
        model.shutdown()
    output = {"label": args.label, "num_ctx": NUM_CTX, "max_tokens": MAX_TOKENS, "real_llm": bool(REAL_LLM),
              "questions": results, "conversation": talk, "rag_log": log_lines}
    Path(args.out).write_text(json.dumps(output, indent=1, ensure_ascii=False))
    print(f"{'id':26} {'hits':>4} {'rel':>3}/{'gold':<4} {'prec':>5} {'rec':>5} {'foreign':>7} {'ctxChars':>8} {'tokens':>6} {'fits':>5} {'ui=ctx':>6}")
    for r in results:
        print(f"{r['id']:26} {r['hits']:>4} {r['relevant']:>3}/{r['gold']:<4} {str(r['precision']):>5} {str(r['recall']):>5} "
              f"{len(r['foreign']):>7} {r['context_chars']:>8} {r['prompt_tokens_est']:>6} {str(r['fits_num_ctx']):>5} {str(r['ui_equals_context']):>6}")
    print("conversation:", [(t["turn"], t["prompt_tokens_est"], t["data_blocks"], t["fits_num_ctx"]) for t in talk["turns"]])
    print(f"PASS: {len(results)} catalog requests and {len(talk['turns'])} conversation turns recorded ({args.label})")


if __name__ == "__main__":
    main()
