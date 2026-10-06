# Knowledge graph A/B benchmark (package 6)

Does the chat answer better when Scoutro adds facts from the knowledge graph to the
search results? Every question is asked twice, with `scoutro.kg.chat.enabled=false`
(A, "off") and `=true` (B, "on"). Index, catalog, model settings and data are the same
in both modes.

**Decision rule of package 6:** the graph facts pay off at **at least +10 percentage
points of fact hits**, with nothing worse on false or invented facts, sources, or
correct "weiß ich nicht".

## Result

| | Run 1 (before the fix) | | Final (after 339452a) | |
|---|---|---|---|---|
| | off | on | off | on |
| Fact hits (answerable facts in the answer) | 80.0 % | 100.0 % | 80.0 % | 100.0 % |
| Facts present in the context | 80.0 % | 100.0 % | 80.0 % | 100.0 % |
| Complete answers (all facts, nothing false) | 75.0 % | 87.5 % | 75.0 % | 100.0 % |
| False facts / invented values | 0 / 0 | 0 / 0 | 0 / 0 | 0 / 0 |
| Values more precise than the source (review) | 0 | **11 in 9 answers** | 0 | 0 |
| Correctly sourced answerable answers | 100 % | 100 % | 100 % | 100 % |
| False citations | 0 | 0 | 0 | 0 |
| Correct "weiß ich nicht" (12 unanswerable answers) | 100 % | 100 % | 100 % | 100 % |
| Honest abstention when the context lacked the fact | 14 of 14 | – | 14 of 14 | – |
| Contradictions in an answer / between repetitions | 0 / 0 | 0 / 0 | 0 / 0 | 0 / 0 |
| Mean answer length (characters) | 614 | 643 | 614 | 646 |
| Mean context (characters / tokens) | 784 / 196 | 1 797 / 534 | 784 / 196 | 1 795 / 533 |
| Graph facts per question (mean) | 0 | 7.5 | 0 | 7.5 |
| Context build time, median / p95 (ms) | 132 / 161 | 145 / 185 | 132 / 163 | 142 / 178 |
| Answer generation time, median (ms) | 4 344 | 4 603 | 4 344 | 4 605 |
| **Decision** | | **fails** (precision) | | **pays off**: +20.0 pp, nothing worse |

The figures above include the manual review. The automatic scoring alone flags 1 invented
value (graph on) and 3 citations of another provider's page (1 off, 2 on). The review
cleared all of them:
- the "invented value" is 71,40 €, a difference over 14 days that the answer introduces as a computation;
- each flagged citation cites another provider's page for a statement about that provider.

Both versions are in `results/score-*.json` (`modes` reviewed, `automatic` without the
review).

**Where the difference comes from.** Per topic (final run, fact hits off → on):

| Topic | off | on |
|---|---|---|
| Contacts: phone, address, e-mail, VAT ID, opening hours | 0 % | 100 % |
| Same-name firms in two collections | 0 % | 100 % |
| Operator/facilities, prices (current, stale, history), services, relations, industry, audiences, jobs, comparison | 100 % | 100 % |
| Collection limit, unanswerable: correct "weiß ich nicht" | 100 % | 100 % |

The whole gain comes from facts that exist only in structured data (JSON-LD contact data,
which Scoutro's text index does not hold) and from telling two firms of the same name apart.
Wherever the page text held the fact, the model found it without the graph. In both modes it
answered every fact its context held, and nothing else.

## Method

- **Corpus** (`corpus.json`): 19 synthetic pages (`.example` hosts, invented firms). They are
  in four collections named after Scoutro's collections, so each set of pages gets its
  collection's vocabulary:
  - `edelsenior-web`: 8 pages, a care operator with two homes, a second home with a carrier,
    current, stale and historic prices, a job;
  - `bauteamcheck-web`: 5 pages, trades with services, a guild membership, a price and a job;
  - `stackfinder-web`: 4 pages, a software firm with customers and audience, and a partner;
  - `checkthecoach-web`: 2 pages, two coaches, one of them named like the care home.

  Every page carries JSON-LD. Some facts appear only there, as on real sites.
- **Catalog** (`catalog.json`): 34 questions in German, each asked in its collection. Each
  question has:
  - per fact a list of regular expressions (one must match);
  - the URLs that support the answer;
  - false values;
  - for 6 unanswerable questions (collection limit, missing data), the trap values a correct
    answer must not contain, and the pattern of a "weiß ich nicht".
- **Harness** (`kg-benchmark.py`): a new disposable peer (temporary `DATA`, 127.0.0.1). It
  indexes the corpus through YaCy's parser and waits for the graph and its derived layer.
  Then it asks every question through the real chat endpoint `/v1/chat/completions`:
  - once with graph facts off, and after a restart on the same `DATA` once with them on;
  - the setting is read before and restored byte for byte afterwards (`chat_setting_before`
    and `chat_setting_restored` in the contexts).

  The configured model is a local endpoint that records exactly the messages Scoutro sends.
  The harness takes no production URL, `DATA` or credential.
- **Answers** (`answer.py`): one task file per question, mode and repetition, holding the
  recorded messages and nothing else. The file name is an opaque hash, so the answering model
  cannot tell the mode.
  - One Claude agent per task (Claude Code subagent, one model for all tasks) read only its
    task file and wrote the reply. `answer.py timings` checks this from the transcripts: no
    run broke the rule. One agent corrected a word in its own answer with a second edit.
  - **2 repetitions**: 34 × 2 × 2 = 136 answers.
  - **No temperature is claimed**: the agents run with the platform's defaults. The
    repetitions show the variance: no question changed its result between them.
  - The model identifier is kept out of the repository and the pull request; the run's
    report to its requester names it.
- **Scoring** (`score.py`): automatic and reproducible. It covers:
  - fact hits, and whether the context held the fact;
  - false values and trap values;
  - invented values: phone numbers, e-mail addresses, VAT IDs, amounts and dates that are not
    in the context;
  - every citation `[n]`, looked up in that mode's source list;
  - abstention, contradictions, length, context size and timings.

  Generation time is the time from the task file being read to the answer being written,
  taken from the transcripts.

  The "weiß ich nicht" pattern of the catalog was widened after the first scoring. All 24
  answers to unanswerable questions abstained correctly when read, but the first pattern
  caught only 12 of them ("kann ich dir … nicht nennen", "keinen Hinweis", "nichts
  gefunden"). The wider pattern catches all 24 and applies to both modes alike.
- **Manual review** (`results/manual-review.json`):
  - every automatic flag;
  - a stratified sample of 28 of the 136 answers (about 20 %: 14 questions drawn with
    `random.Random(6)`, both modes of one repetition), read against catalog, context and
    sources;
  - all 16 re-answers.

  In the sample, 28 of 28 verdicts agree with the automatic fact and abstention scores. The
  review found what the automatic scoring could not: dates more precise than the page. The
  reviewer is the Claude session that implemented package 6, not a human, and it belongs to
  the same model family as the answering agents. An LLM-as-judge was not used. A human look at
  the sample is still recommended.

## Findings and fixes

1. **Before the run (dry run, `10e7c4c`).** A first dry run on this catalog showed:
   - the asked-for fact cut off by `scoutro.kg.chat.maxFacts` (facts now follow the
     question's intent);
   - stale prices dropped (now kept, marked, last);
   - two same-name services indistinguishable (now named with their provider);
   - a partner sentence not extracted;
   - a price heading in a label.

   These fixes were found on the same catalog that is scored here, so the result can be
   fitted to these 34 questions. The real collections are the check against that.
2. **In the run (`339452a`).** A stated month ("Stand: 09/2026") reached the model as
   "as of 2026-09-30 (stated on the page)". 9 of 68 graph-on answers repeated a day the page
   never named. The chat facts now give the date as precise as the page. The 8 questions
   whose graph-on context changed (only these dates) were answered again: 16 answers, all
   correct, none with such a day. The graph-off contexts were identical in both captures.
3. **Open, not fixed in package 6.** The rule extractor adds vocabulary-level services
   ("Sanitär", "Sanierung", "Heizung") next to the specific ones. Answers list them; they are
   not false, but less precise.

## Limits

- **Synthetic and small.** 19 pages, 34 questions, one answering model. The +20 pp depends on
  how many asked facts exist only in structured data: here 7 of 28 answerable questions. In
  a large index the retrieval may also miss the right page more often, and graph facts that
  belong to the entity could then help more. The real collections are needed for the size
  of the effect.
- **Real collections: not evaluated.**
  - The production Scoutro 0.7.0 on Olares (`edelsenior-web`, `stackfinder-web`,
    `bauteamcheck-web`, `checkthecoach-web`) cannot be reached from the environment that ran
    this benchmark. It has no address of the instance, no login and no network path to it.
    No credential was written to the repository.
  - `checkthecoach-web` is therefore also "nicht auswertbar". Whether it has enough documents
    could not be checked.
  - A production run through existing interfaces needs:
    1. an administrator login (Digest) for reading and restoring `scoutro.kg.chat.enabled`;
    2. the chat route of the instance;
    3. network access to it.

    On 0.7.0 the chat answers with the instance's own model, so Claude can be the tester and
    judge there, not the answering model. Scoutro has no route that returns the assembled
    context without calling the model.
- **Latency.** Context build time is measured by Scoutro itself. Generation time is the
  answering agent's, not that of the production model.

## Running it again

```sh
# contexts (needs a build: ant compile; a free local port; no network)
python3 test/scoutro-kg-benchmark/kg-benchmark.py --out contexts.json
# tasks for any model, answers as ANSWERDIR/<task>.txt
python3 test/scoutro-kg-benchmark/answer.py tasks contexts.json tasks --repetitions 2
python3 test/scoutro-kg-benchmark/answer.py collect tasks answers answers.json
# optional: generation times from Claude Code subagent transcripts
python3 test/scoutro-kg-benchmark/answer.py timings TRANSCRIPTDIR timings.json
python3 test/scoutro-kg-benchmark/score.py test/scoutro-kg-benchmark/catalog.json contexts.json answers.json score.json \
    --timings timings.json --review test/scoutro-kg-benchmark/results/manual-review.json
```

`answer.py tasks … --run LABEL --only Q09,Q10 --modes on` and `answer.py merge` answer only
the cells whose context changed. They were used for the final run.

**Files in `results/`:**
- `contexts-run1.json` and `contexts-final.json`: the recorded messages;
- `answers-run1.json` and `answers-final.json`;
- `timings.json`;
- `manual-review.json`;
- `score-run1.json` and `score-final.json`: per answer, per question, topic and collection;
- `summary.json`.
