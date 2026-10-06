#!/usr/bin/env python3
"""Prepares the answer tasks of the knowledge graph A/B benchmark and collects the answers.

  answer.py tasks   CONTEXTS.json TASKDIR [--repetitions 2] [--run LABEL] [--only Q01,Q02] [--modes off,on]
      one file per question, mode (graph facts off/on) and repetition: exactly the messages
      Scoutro sends to its chat model, nothing else (no gold, no mode label in the file name
      the answering model sees beyond an opaque id); --run gives a later run its own ids,
      --only and --modes restrict it to the cells whose context changed
  answer.py collect TASKDIR ANSWERDIR OUT.json
      gathers the answers (ANSWERDIR/<id>.txt) with their task's question, mode and repetition
  answer.py merge   BASE.json LATER.json OUT.json
      the answers of BASE with every cell (question, mode, repetition) that LATER answered again
      replaced by LATER's answer
  answer.py timings TRANSCRIPTDIR OUT.json [--with-model]
      reads the transcripts of the answering agents (Claude Code subagent JSONL, one per task):
      the generation time (task file read -> answer written), whether the agent kept to its
      rules (only its task file read, only its answer written) and whether one model answered
      all tasks; the model identifier itself only with --with-model (it stays out of the
      repository; the run's report names it)

Any model can answer the tasks: in the package 6 run, one Claude agent per task answered it
from the task file alone. GPL-2.0-or-later.
"""
from datetime import datetime
import hashlib
import json
from pathlib import Path
import re
import sys


def task_id(qid, mode, rep, run=""):
    # opaque, so that a file name does not tell the answering model which mode it sees
    return hashlib.sha256(f"kg-benchmark/{run + '/' if run else ''}{qid}/{mode}/{rep}".encode()).hexdigest()[:12]


def tasks(contexts, taskdir, repetitions, run="", only=None, modes=("off", "on")):
    data = json.loads(Path(contexts).read_text())
    out = Path(taskdir)
    out.mkdir(parents=True, exist_ok=True)
    index = []
    for q in data["questions"]:
        if only and q["id"] not in only:
            continue
        for mode in modes:
            for rep in range(1, repetitions + 1):
                tid = task_id(q["id"], mode, rep, run)
                (out / f"{tid}.json").write_text(json.dumps({"messages": q[mode]["messages"]}, ensure_ascii=False, indent=1) + "\n")
                index.append({"task": tid, "question": q["id"], "mode": mode, "repetition": rep, **({"run": run} if run else {})})
    # the index stays apart from the task files
    (out.parent / (out.name + "-index.json")).write_text(json.dumps(index, indent=1) + "\n")
    print(f"{len(index)} tasks in {out}")


def collect(taskdir, answerdir, out):
    index = json.loads((Path(taskdir).parent / (Path(taskdir).name + "-index.json")).read_text())
    rows, missing = [], []
    for t in index:
        f = Path(answerdir) / f"{t['task']}.txt"
        if not f.is_file():
            missing.append(t["task"])
            continue
        rows.append(dict(t, answer=f.read_text().strip()))
    Path(out).write_text(json.dumps({"answers": rows, "missing": missing}, ensure_ascii=False, indent=1) + "\n")
    print(f"{len(rows)} answers collected, {len(missing)} missing")


def merge(base, later, out):
    b = json.loads(Path(base).read_text())
    l = json.loads(Path(later).read_text())
    key = lambda a: (a["question"], a["mode"], a["repetition"])
    again = {key(a): a for a in l["answers"]}
    rows = [again.get(key(a), a) for a in b["answers"]]
    Path(out).write_text(json.dumps({"answers": rows, "missing": b["missing"] + l["missing"],
                                     "replaced": sorted(a["task"] for a in l["answers"])}, ensure_ascii=False, indent=1) + "\n")
    print(f"{len(rows)} answers, {len(again)} replaced")


def timings(transcripts, out, with_model=False):
    def ts(s):
        return datetime.fromisoformat(s.replace("Z", "+00:00")).timestamp()
    rows = {}
    for f in sorted(Path(transcripts).glob("*.output")):
        lines = []
        for line in f.read_text(errors="replace").splitlines():
            try:
                o = json.loads(line)
            except ValueError:
                continue
            if isinstance(o, dict):
                lines.append(o)
        first = next((o for o in lines if o.get("type") == "user"), None)
        content = first and first.get("message", {}).get("content")
        m = isinstance(content, str) and re.search(r"tasks/([0-9a-f]{12})\.json", content)
        if not m or "Benchmark task" not in content:
            continue
        tid = m.group(1)
        tools, models, read_done, written = [], set(), None, None
        for o in lines:
            msg = o.get("message", {})
            if msg.get("model"):
                models.add(msg["model"])
            for x in msg.get("content") if isinstance(msg.get("content"), list) else []:
                if x.get("type") == "tool_use":
                    tools.append((x["name"], json.dumps(x.get("input", {}))))
                    if x["name"] == "Write" and written is None:
                        written = ts(o["timestamp"])
                if x.get("type") == "tool_result" and read_done is None:
                    read_done = ts(o["timestamp"])
        own = f"/{tid}."
        kept = all(n in ("Read", "Write", "Edit", "SubagentHandback") for n, _ in tools) \
            and all(own in i for n, i in tools if n in ("Read", "Write", "Edit"))
        row = {"tools": [n for n, _ in tools], "rules_kept": kept,
               "generation_ms": round((written - read_done) * 1000) if written and read_done else None, "_models": sorted(models)}
        rows.setdefault(tid, []).append(row)
    distinct = {m for v in rows.values() for r in v for m in r["_models"]}
    for v in rows.values():
        for r in v:
            models = r.pop("_models")
            if with_model:
                r["models"] = models
    doc = {"one_model_for_all_tasks": len(distinct) == 1, "tasks": rows}
    Path(out).write_text(json.dumps(doc, indent=1) + "\n")
    print(f"{len(rows)} tasks, {sum(not r['rules_kept'] for v in rows.values() for r in v)} runs broke a rule, "
          f"{len(distinct)} distinct model(s)" + (": " + ", ".join(sorted(distinct)) if with_model else ""))


if __name__ == "__main__":
    def opt(name, default=None):
        return sys.argv[sys.argv.index(name) + 1] if name in sys.argv else default
    if len(sys.argv) >= 4 and sys.argv[1] == "tasks":
        only = opt("--only")
        tasks(sys.argv[2], sys.argv[3], int(opt("--repetitions", "2")), opt("--run", ""),
              set(only.split(",")) if only else None, tuple(opt("--modes", "off,on").split(",")))
    elif len(sys.argv) == 5 and sys.argv[1] == "merge":
        merge(sys.argv[2], sys.argv[3], sys.argv[4])
    elif len(sys.argv) == 5 and sys.argv[1] == "collect":
        collect(sys.argv[2], sys.argv[3], sys.argv[4])
    elif len(sys.argv) in (4, 5) and sys.argv[1] == "timings":
        timings(sys.argv[2], sys.argv[3], "--with-model" in sys.argv[4:])
    else:
        print(__doc__)
        sys.exit(2)
