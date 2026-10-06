#!/usr/bin/env python3
"""Scores the answers of the knowledge graph A/B benchmark (package 6).

  score.py CATALOG.json CONTEXTS.json ANSWERS.json OUT.json [--timings TIMINGS.json] [--review REVIEW.json]

CONTEXTS.json is what kg-benchmark.py captured (the messages per question and mode),
ANSWERS.json what answer.py collect gathered, TIMINGS.json optional per task generation times,
REVIEW.json the manual verdicts on the flags the automatic scoring raises (see below).

Per answer, all automatic and reproducible:
  fact hits      each catalog fact needs one of its regexes in the answer
  false facts    a 'wrong' regex of the question, or a trap value of an unanswerable one
  invented       phone numbers, e-mail addresses, VAT IDs, amounts and dates in the answer
                 that the context the model received does not hold (a difference or sum of
                 two amounts of the context counts as derived, not invented)
  coverage       a fact is in the context when one of its regexes matches what the model got
  sources        every [n] is looked up in that mode's source list: an answerable answer is
                 correctly sourced when it cites a page of the catalog; a citation is false
                 when the number is not listed; a citation of another provider's page is
                 flagged for review (it is false only when the review says it supports a
                 statement about the asked provider)
  "weiß ich nicht"  an unanswerable question is answered correctly when the answer says the
                 data does not hold it and names no trap value; an answerable one is a false
                 abstention when it only abstains although its context held a fact, and an
                 honest one when the context held none of its facts
  contradictions a fact and a false value in one answer; the repetitions of a question
                 disagreeing (other facts, or answer vs. abstention)
  length, context characters, context build time, generation time (when given)

The review file holds a verdict per flagged item ({"task", "kind": "invented" |
"foreign_citation" | "imprecise", "value", "verdict": "derived" | "correct" | "confirmed",
"reason"}): 'derived' or 'correct' clears the flag, 'confirmed' keeps it. 'imprecise' is what
only a reader finds: a value more precise than its source (a day for a stated month); it counts
against the mode like a false fact. Both the automatic and the reviewed figures are written.

Decision rule of the package: the graph facts pay off at >= +10 percentage points fact hits
without getting worse on false or invented facts, sources or correct abstentions.
GPL-2.0-or-later.
"""
from collections import defaultdict
import json
from pathlib import Path
import re
import statistics
import sys

TOPICS = {
    "phone": "contacts", "address": "contacts", "email": "contacts", "vat": "contacts", "hours": "contacts",
    "operator": "operator/facilities", "facilities": "operator/facilities", "carrier": "operator/facilities",
    "price": "prices", "price-stale": "prices", "stale-history": "prices",
    "services": "services", "relation": "relations", "industry": "industry", "audience": "audiences",
    "jobs": "jobs", "compare": "comparison", "same-name": "same name",
    "collection-limit": "collection limit", "unanswerable": "unanswerable",
}

CITE = re.compile(r"\[(\d+(?:\s*[,;]\s*\d+)*)\]")
EMAIL = re.compile(r"[\w.+-]+@[\w-]+(?:\.[\w-]+)+", re.I)
VAT = re.compile(r"\bDE\s?\d{3}\s?\d{3}\s?\d{3}\b")
PHONE = re.compile(r"(?<![\w.,])(?:\+\s?49[\s/()-]*|0)\d{2,5}(?:[\s/()-]*\d){3,10}")
NUM = r"\d{1,3}(?:\.\d{3})+(?:,\d{1,2})?|\d+(?:[.,]\d{1,2})?"
AMOUNT = re.compile(r"(?<![\d.,])(" + NUM + r")(?:\s*(?:[–-]|bis)\s*(" + NUM + r"))?\s*(?:€|Euro|EUR)", re.I)
DMY = re.compile(r"\b(\d{1,2})\.(\d{1,2})\.(\d{4})\b")
MY = re.compile(r"(?<![\d.])(\d{1,2})/(\d{4})\b")
ISO = re.compile(r"\b(\d{4})-(\d{2})(?:-(\d{2}))?\b")
CTX_NUM = re.compile(r"(?<![\d])(\d{1,3}(?:\.\d{3})+(?:,\d+)?|\d+(?:[.,]\d+)?)")


def phone_key(s):
    d = re.sub(r"\D", "", s)
    if s.lstrip().startswith("+"):
        d = "0" + d[2:] if d.startswith("49") else d
    return d


def number(s):
    """German or English notation: '3.400' and '89,90' and '89.90'."""
    if re.fullmatch(r"\d{1,3}(?:\.\d{3})+(?:,\d+)?", s):
        return float(s.replace(".", "").replace(",", "."))
    return float(s.replace(",", "."))


def context_values(text):
    nums = {round(number(m), 2) for m in CTX_NUM.findall(text)}
    dates, months = set(), set()
    for d, m, y in DMY.findall(text):
        dates.add((int(y), int(m), int(d)))
        months.add((int(y), int(m)))
    for m, y in MY.findall(text):
        months.add((int(y), int(m)))
    for y, m, d in ISO.findall(text):
        months.add((int(y), int(m)))
        if d:
            dates.add((int(y), int(m), int(d)))
    return {
        "emails": {e.lower().rstrip(".") for e in EMAIL.findall(text)},
        "vat": {re.sub(r"\s", "", v) for v in VAT.findall(text)},
        "phones": {phone_key(p) for p in PHONE.findall(text)},
        "digits": re.sub(r"\D", "", text),
        "numbers": nums,
        "dates": dates,
        "months": months,
    }


def invented(answer, ctx):
    out = []
    for e in EMAIL.findall(answer):
        if e.lower().rstrip(".") not in ctx["emails"]:
            out.append(e)
    for v in VAT.findall(answer):
        if re.sub(r"\s", "", v) not in ctx["vat"]:
            out.append(v)
    for p in PHONE.findall(answer):
        k = phone_key(p)
        if len(k) >= 9 and k not in ctx["phones"] and k not in ctx["digits"]:
            out.append(p.strip())
    nums = ctx["numbers"]
    derived = {round(abs(a - b), 2) for a in nums for b in nums} | {round(a + b, 2) for a in nums for b in nums}
    for m in AMOUNT.finditer(answer):
        for g in (m.group(1), m.group(2)):
            if g and round(number(g), 2) not in nums and round(number(g), 2) not in derived:
                out.append(m.group(0))
    for d, m, y in DMY.findall(answer):
        if (int(y), int(m), int(d)) not in ctx["dates"]:
            out.append(f"{d}.{m}.{y}")
    for m, y in MY.findall(answer):
        if (int(y), int(m)) not in ctx["months"]:
            out.append(f"{m}/{y}")
    return out


def host(url):
    return re.sub(r"^https?://(www\.)?", "", url).split("/")[0]


def score_answer(q, mode_ctx, answer, abstention):
    text = mode_ctx["messages"][-1]["content"]
    ctx = context_values(text)
    sources = {s["id"]: s["url"] for s in mode_ctx["sources"]}
    hits = [f["label"] for f in q["facts"] if any(re.search(r, answer, re.I) for r in f["any"])]
    covered = [f["label"] for f in q["facts"] if any(re.search(r, text, re.I) for r in f["any"])]
    wrong = [r for r in q["wrong"] if re.search(r, answer, re.I)]
    traps = [r for r in q["trap"] if re.search(r, answer, re.I)]
    inv = invented(answer, ctx)
    cited = sorted({int(n) for g in CITE.findall(answer) for n in re.split(r"\s*[,;]\s*", g)})
    expected_hosts = {e.split("/")[0] for e in q["sources"]}
    false_cites, foreign, good = [], [], False
    for n in cited:
        url = sources.get(n)
        if url is None:
            false_cites.append(f"[{n}] not listed")
            continue
        if any(e in url for e in q["sources"]):
            good = True
        if expected_hosts and host(url) not in expected_hosts:
            foreign.append(f"[{n}] {host(url)}")
    abstains = bool(re.search(abstention, answer))
    row = {
        "facts": len(q["facts"]), "hits": hits, "covered": covered, "false": wrong + traps, "invented": inv,
        "cited": cited, "correct_source": good if q["facts"] else None, "false_sources": false_cites,
        "foreign_citations": foreign,
        "abstains": abstains, "chars": len(answer), "words": len(answer.split()),
        "context_chars": len(text), "context_tokens": mode_ctx["retrieval"].get("contextTokens"),
        "context_ms": mode_ctx["context_ms"], "graph_facts": (mode_ctx.get("graph") or {}).get("facts", 0),
    }
    if q["unanswerable"]:
        row["correct_abstention"] = abstains and not traps
    else:
        row["false_abstention"] = abstains and not hits and bool(covered)
        row["honest_abstention"] = abstains and not covered and not wrong and not inv
        row["contradiction"] = bool(hits and wrong)
        row["complete"] = len(hits) == len(q["facts"]) and not wrong and not inv
    return row


def pct(a, b):
    return round(100.0 * a / b, 1) if b else None


def aggregate(rows):
    ans = [r for r in rows if not r["unanswerable"]]
    una = [r for r in rows if r["unanswerable"]]
    facts = sum(r["facts"] for r in ans)
    g = [r["generation_ms"] for r in rows if r.get("generation_ms")]
    ms = sorted(r["context_ms"] for r in rows)
    lacking = [r for r in ans if not r["covered"]]
    return {
        "answers": len(rows),
        "answerable_answers": len(ans), "unanswerable_answers": len(una),
        "fact_hit_rate": pct(sum(len(r["hits"]) for r in ans), facts),
        "context_fact_coverage": pct(sum(len(r["covered"]) for r in ans), facts),
        "facts_in_context_missed": sum(len(set(r["covered"]) - set(r["hits"])) for r in ans),
        "complete_answer_rate": pct(sum(r["complete"] for r in ans), len(ans)),
        "false_facts": sum(len(r["false"]) for r in rows),
        "answers_with_false_facts": sum(bool(r["false"]) for r in rows),
        "invented_values": sum(len(r["invented"]) for r in rows),
        "answers_with_invented_values": sum(bool(r["invented"]) for r in rows),
        "hallucination_rate": pct(sum(bool(r["false"] or r["invented"]) for r in rows), len(rows)),
        "imprecise_values": sum(len(r.get("imprecise", [])) for r in rows),
        "answers_with_imprecise_values": sum(bool(r.get("imprecise")) for r in rows),
        "correct_source_rate": pct(sum(bool(r["correct_source"]) for r in ans), len(ans)),
        "false_sources": sum(len(r["false_sources"]) for r in rows),
        "answers_with_false_sources": sum(bool(r["false_sources"]) for r in rows),
        "foreign_citations_flagged": sum(len(r["foreign_citations"]) for r in rows),
        "correct_abstention_rate": pct(sum(r["correct_abstention"] for r in una), len(una)),
        "false_abstentions": sum(r["false_abstention"] for r in ans),
        "answerable_without_fact_in_context": len(lacking),
        "honest_abstentions": sum(r["honest_abstention"] for r in lacking),
        "contradictions_in_answer": sum(r["contradiction"] for r in ans),
        "mean_answer_chars": round(statistics.mean(r["chars"] for r in rows)) if rows else None,
        "mean_answer_words": round(statistics.mean(r["words"] for r in rows)) if rows else None,
        "mean_context_chars": round(statistics.mean(r["context_chars"] for r in rows)) if rows else None,
        "mean_context_tokens": round(statistics.mean(r["context_tokens"] or 0 for r in rows)) if rows else None,
        "mean_graph_facts": round(statistics.mean(r["graph_facts"] for r in rows), 1) if rows else None,
        "context_ms_median": statistics.median(ms) if ms else None,
        "context_ms_p95": ms[int(0.95 * (len(ms) - 1))] if ms else None,
        "generation_ms_median": statistics.median(g) if g else None,
    }


def inconsistency(rows):
    by = defaultdict(list)
    for r in rows:
        by[r["question"]].append(r)
    n = 0
    out = []
    for qid, rs in by.items():
        keys = {(tuple(sorted(r["hits"])), r["abstains"] and not r["hits"]) for r in rs}
        if len(keys) > 1:
            n += 1
            out.append(qid)
    return n, out


def apply_review(rows, review):
    """Moves foreign citations to false sources unless cleared; drops invented values cleared as derived."""
    verdicts = {(i["task"], i["kind"], i["value"]): i["verdict"] for i in review.get("items", [])}
    unreviewed = []
    out = []
    for r in rows:
        r = dict(r)
        keep_inv = []
        for v in r["invented"]:
            verdict = verdicts.get((r["task"], "invented", v))
            if verdict is None:
                unreviewed.append((r["task"], "invented", v))
            if verdict not in ("derived", "correct"):
                keep_inv.append(v)
        r["invented"] = keep_inv
        fs = list(r["false_sources"])
        for c in r["foreign_citations"]:
            verdict = verdicts.get((r["task"], "foreign_citation", c))
            if verdict is None:
                unreviewed.append((r["task"], "foreign_citation", c))
            if verdict not in ("correct",):
                fs.append(c)
        r["false_sources"] = fs
        r["imprecise"] = [i["value"] for i in review.get("items", []) if i["task"] == r["task"] and i["kind"] == "imprecise"
                          and i["verdict"] == "confirmed"]
        if not r["unanswerable"]:
            r["honest_abstention"] = r["abstains"] and not r["covered"] and not r["false"] and not r["invented"]
            r["complete"] = len(r["hits"]) == r["facts"] and not r["false"] and not r["invented"] and not r["imprecise"]
        out.append(r)
    return out, unreviewed


def summary(rows):
    modes = {m: aggregate([r for r in rows if r["mode"] == m]) for m in ("off", "on")}
    for m in modes:
        modes[m]["inconsistent_questions"], modes[m]["inconsistent_list"] = inconsistency([r for r in rows if r["mode"] == m])
    off, on = modes["off"], modes["on"]
    worse = {
        "hallucination": on["hallucination_rate"] > off["hallucination_rate"],
        "false_facts": on["false_facts"] > off["false_facts"],
        "invented_values": on["invented_values"] > off["invented_values"],
        "imprecise_values": on["imprecise_values"] > off["imprecise_values"],
        "correct_sources": on["correct_source_rate"] < off["correct_source_rate"],
        "false_sources": on["false_sources"] > off["false_sources"],
        "correct_abstention": on["correct_abstention_rate"] < off["correct_abstention_rate"],
    }
    delta = round(on["fact_hit_rate"] - off["fact_hit_rate"], 1)
    decision = {
        "fact_hit_delta_pp": delta,
        "required_pp": 10,
        "worse_on": [k for k, v in worse.items() if v],
        "pays_off": delta >= 10 and not any(worse.values()),
    }
    return modes, decision


def main(catalog, contexts, answers, out, timings=None, review=None):
    cat = json.loads(Path(catalog).read_text())
    ctx = {q["id"]: q for q in json.loads(Path(contexts).read_text())["questions"]}
    data = json.loads(Path(answers).read_text())
    gen = {}
    if timings:
        doc = json.loads(Path(timings).read_text())
        for tid, runs in doc.get("tasks", doc).items():
            gen[tid] = runs[0].get("generation_ms")
    qs = {q["id"]: q for q in cat["questions"]}
    rows = []
    for a in data["answers"]:
        q = qs[a["question"]]
        r = score_answer(q, ctx[q["id"]][a["mode"]], a["answer"], cat["abstention"])
        r.update(task=a["task"], question=q["id"], topic=TOPICS.get(q["topic"], q["topic"]), collection=q["collection"],
                 mode=a["mode"], repetition=a["repetition"], unanswerable=q["unanswerable"], generation_ms=gen.get(a["task"]))
        rows.append(r)
    auto_rows, _ = apply_review(rows, {})
    automatic, auto_decision = summary(auto_rows)
    unreviewed = []
    if review:
        rows, unreviewed = apply_review(rows, json.loads(Path(review).read_text()))
    else:
        rows = auto_rows
    modes, decision = summary(rows)
    groups = {}
    for key in ("topic", "collection"):
        groups[key] = {}
        for g in sorted({r[key] for r in rows}):
            groups[key][g] = {m: aggregate([r for r in rows if r[key] == g and r["mode"] == m]) for m in ("off", "on")}
    per_question = {}
    for qid in qs:
        per_question[qid] = {}
        for m in ("off", "on"):
            rs = [r for r in rows if r["question"] == qid and r["mode"] == m]
            per_question[qid][m] = {
                "hits": [len(r["hits"]) for r in rs], "facts": len(qs[qid]["facts"]),
                "covered": max((len(r["covered"]) for r in rs), default=0),
                "false": sum(len(r["false"]) for r in rs), "invented": sum(len(r["invented"]) for r in rs),
                "false_sources": sum(len(r["false_sources"]) for r in rs),
                "abstains": [r["abstains"] for r in rs],
            }
    result = {"reviewed": bool(review), "unreviewed_flags": unreviewed, "modes": modes, "decision": decision,
              "automatic": {"modes": automatic, "decision": auto_decision}, "by": groups,
              "per_question": per_question, "answers": rows}
    Path(out).write_text(json.dumps(result, ensure_ascii=False, indent=1) + "\n")
    for m in ("off", "on"):
        print(m, json.dumps({k: v for k, v in modes[m].items() if k != "inconsistent_list"}, ensure_ascii=False))
    print("decision", json.dumps(decision))
    print("automatic decision", json.dumps(auto_decision))
    if unreviewed:
        print("unreviewed flags:", json.dumps(unreviewed, ensure_ascii=False))


if __name__ == "__main__":
    args = sys.argv[1:]
    opts = {}
    for flag in ("--timings", "--review"):
        if flag in args:
            i = args.index(flag)
            opts[flag[2:]] = args[i + 1]
            del args[i:i + 2]
    if len(args) != 4:
        print(__doc__)
        sys.exit(2)
    main(*args, **opts)
