# graphrag-akka

Groups a graph of things and the links between them into a nested set of groups, writes a
short description of every group, and where a description will not fit inside a size limit,
replaces its detail with the summaries of the smaller groups inside it.

A port of [microsoft/graphrag](https://github.com/microsoft/graphrag) onto **Akka**, built
with **Akka Specify**.

---

## Where it came from

microsoft/graphrag reads a pile of documents, pulls out the things they mention and how
those things are connected, groups the result, and answers questions against the groups
rather than against the raw text. This port rebuilds one part of that: the grouping, and
the summaries that roll up from the small groups into the big ones.

The specifications the port was generated from are in
[TylerJewell/akka-specify-harness](https://github.com/TylerJewell/akka-specify-harness)
under `graphrag-port/`.

---

## microsoft/graphrag → this port

📉 930 Python lines → **972 Java lines**<br>
📁 12 files → **10 files**<br>
⚡ 359 milliseconds → **11 milliseconds**, describing 121 groups against a small size limit<br>
⚡ 1,860 milliseconds → **330 milliseconds**, the same against a large one<br>
⚡ 128 milliseconds → **4 milliseconds**, rolling the whole nest of groups up<br>
⚡ 95 milliseconds → **0.8 milliseconds**, preparing 978 links for grouping<br>
🎯 121 of 121 groups identical → **121 of 121**, at three different size limits<br>
🧪 0 checks over this behaviour → **57**

Full method and the numbers that did *not* make this list:
[`bench/REPORT.md`](https://github.com/TylerJewell/akka-specify-harness/blob/main/graphrag-port/bench/REPORT.md).

---

## What it took to build

⏱️ **2.2 hours** from the first command to the published repository, **2.2** of them active<br>
💬 **456** exchanges with the model<br>
✍️ **537,646** tokens written by the model, **160,792,177** counting everything sent and re-sent<br>
🙋 **0** questions to a human<br>
🧪 **57** tests

```bash
python toolkit/tokens.py --port graphrag    # turns, tokens, elapsed and active time
```

The record of every question, and where the time went, is in
[`port-log/`](https://github.com/TylerJewell/akka-specify-harness/tree/main/port-log).

---

## What it does

From the specification:

- **A group's links are the ones with both ends inside it.** A link that leaves the group
  belongs to neither group it touches, so counting it would credit both.
- **Not every link a group has gets described.** Each thing in a group carries at most one
  link into the group's description, and a link is eligible because both its ends are at
  the same depth, not because both are in the same group. On the original's own example
  that means 1,093 of 1,301 available links are described, 223 of them describing something
  outside the group they appear in.
- **A description is built strongest link first and cut where it stops fitting.** Links are
  taken in order of how well connected they are, the description is rebuilt and re-measured
  after every one, and the first rebuild that overruns is thrown away.
- **A description that cannot fit at any size is returned whole rather than empty.** A group
  with something in it never comes back with nothing.
- **The roll-up goes from the deepest groups outward.** A group whose own detail overruns
  has it replaced by the summaries of the groups inside it, biggest first, one at a time,
  until what is left fits.
- **A group inside it with no summary of its own contributes its detail instead**, and does
  not use up one of those replacements.
- **The same input submitted in a different order can give a different answer.** Where two
  links join the same pair of things, the one that arrived last is the one kept, so which
  arrived last decides which survives.

---

## Design decisions

**Groups are found somewhere else.** The original hands the actual grouping to a piece of
software written by other people in another language, which this port cannot match answer
for answer because it uses randomness of its own. Everything the original does around that
call is rebuilt exactly, and the grouping itself is handed in, so every answer here can be
checked against the original's rather than argued about.

**Descriptions are written by something you supply.** Writing the words is a job for a
language model, and a speed measurement that includes one measures the model. What is
rebuilt is everything that decides what goes into a description and where it gets cut; the
part that turns that into prose is a slot you fill.

**A run is stored group by group.** Everything a run knows about one group — what is in it,
its description, its summary — is kept together and on its own. Nothing has to hold the
whole run at once, so a run that dies part-way picks up where it stopped instead of
starting again.

**The graph arrives in pieces.** The original's own example is 798 kilobytes and the
platform refuses a single message over about a megabyte, loudly and by name. Sending it in
batches means a graph bigger than the example is a longer submission rather than a failure.

**A submission sent twice adds nothing.** Rows are recognised by their own identity, so a
client that retries after a timeout does not end up with two copies of its graph. Two links
joining the same pair of things are still two links, because the answer depends on both
being there.

---

## Running it — the short path

You do not need Java, Maven, or the Akka CLI installed. Akka Specify installs them for you.

**1. Install Akka Specify** in Claude Code:

```
/plugin marketplace add akka/ai-marketplace
/plugin install akka@akka-ai-marketplace
```

Restart Claude Code when it asks.

**2. Give it this prompt:**

> Clone https://github.com/TylerJewell/graphrag-akka into a new directory and open it.
> Then run /akka:setup to install everything this project needs, and /akka:build to
> compile it, run the tests, and start it locally.

**3. Send it a graph** — see the next section.

---

## Running it — the developer path

### Requirements

- Java 21 or newer
- Maven 3.9 or newer
- An Akka download token — run `akka code token` once

### Start the service

```bash
mvn compile
akka local run
```

The service starts on **port 9046**.

### Send it a graph

Submit the things, the links and the grouping in batches, close the graph, then run it.

```bash
RUN=run-1

curl -X POST localhost:9046/index/$RUN/graph/entities \
  -H 'Content-Type: application/json' -d @entities.json
curl -X POST localhost:9046/index/$RUN/graph/relationships \
  -H 'Content-Type: application/json' -d @relationships.json
curl -X POST localhost:9046/index/$RUN/graph/clusters \
  -H 'Content-Type: application/json' -d @clusters.json
curl -X POST localhost:9046/index/$RUN/graph/seal

curl -X POST localhost:9046/index/$RUN/run \
  -H 'Content-Type: application/json' \
  -d '{"maxContextTokens": 16000, "driver": "SOURCE_ORDER"}'

curl localhost:9046/index/$RUN/status
curl localhost:9046/index/$RUN/communities
curl localhost:9046/index/$RUN/communities/7
```

There is also a route that only prepares links for grouping, without running anything:

```bash
curl -X POST localhost:9046/index/edge-list \
  -H 'Content-Type: application/json' \
  -d '{"relationships": [...], "useLargestConnectedComponent": true}'
```

### Run the checks

```bash
mvn test
```

The 57 checks compare this port's answers against the original's, read from files the
harness repository produces by running the original. Without that repository beside this
one they cannot run, and they say so rather than passing.

---

## Model providers

This port calls no model. Writing the words of a summary is the one place a model would be
used, and that is a slot you fill: `LevelRollup.ReportWriter` takes a group number, a depth
and a finished description, and returns the text. The one that ships builds a fixed string,
so the checks and the speed measurements are about this port rather than about a model.

Nothing here reads an API key, and no provider configuration is needed to run it.

---

## Configuration

| Variable | Default | Notes |
|---|---|---|
| `maxContextTokens` (per run) | 16000 | The size limit a description is cut to. Sent in the body of `POST /index/{runId}/run`. |
| `driver` (per run) | `SOURCE_ORDER` | `SOURCE_ORDER` matches the original. `FEED_REPORTS_FORWARD` lets each depth see the summaries written for the depth below it, which the original never does. |
| `useLargestConnectedComponent` (per request) | `true` | Whether to keep only the biggest connected piece of the graph. Sent to `POST /index/edge-list`. |
| `tieBreakOnId` (per request) | `false` | Whether to settle duplicate links by their identity instead of by arrival order. Not what the original does. |
| `akka.javasdk.dev-mode.http-port` | 9046 | The port the service listens on locally. |

---

## Where it differs from microsoft/graphrag

Everything not listed here behaves the same way on purpose, including the parts that look
like mistakes.

- **The grouping itself is not computed here.** microsoft/graphrag calls a hierarchical
  grouping algorithm shipped as a compiled library written in another language. This port
  takes the grouping as an input instead, because a fresh implementation would make its own
  random choices and could not be checked against the original's answers — which is the one
  thing every other part of this port can be.
- **No summary text is written.** microsoft/graphrag asks a language model to write each
  group's summary. This port takes something that writes them and ships one that builds a
  fixed string, so that a measurement of this code is not a measurement of a model.
- **A group's identity is worked out rather than invented.** microsoft/graphrag stamps each
  group with a fresh random identifier, so running it twice over the same graph produces
  different rows. This port derives the identifier from the run and the group number, so the
  same graph gives the same rows twice.
- **The date on a group comes from a clock that is handed in.** microsoft/graphrag stamps
  today's date at the moment it runs. This port takes the date as an input, so a run can be
  repeated and compared.
- **A graph must be submitted in pieces and then closed.** microsoft/graphrag reads its
  graph from files on disk in one go. This port takes it over a network in batches and will
  not start until told the graph is complete, because the platform refuses a single message
  over about a megabyte.
- **A graph can be too big to accept.** This port refuses a graph past 20,000 rows with a
  message saying so. microsoft/graphrag has no such limit. Past roughly that size the stored
  graph would stop being copied between regions, and failing at submission is easier to act
  on than failing later.
- **Submitting the same rows twice adds nothing the second time.** microsoft/graphrag reads
  each file once and has no equivalent situation. This port recognises rows by their own
  identity, because a client that retries a timed-out submission would otherwise double its
  graph.
- **There is an option to settle duplicate links by identity.** microsoft/graphrag keeps
  whichever of two links joining the same pair arrived last, so the answer depends on the
  order they were submitted in. This port does the same by default and offers the other
  behaviour as a setting that is off, because turning it on changes the answers rather than
  preserving them.
- **There is a second way of running the roll-up.** microsoft/graphrag builds every depth's
  description before writing any summary, so no depth ever sees a summary and the
  replacement step never runs. This port does the same by default and offers a setting that
  feeds summaries from one depth to the next, which changes 14 of 121 answers at a small
  size limit and none at a large one.
- **Facts attached to individual things are accepted but never appear.** microsoft/graphrag
  attaches them in a shape its own renderer does not read, so supplying its own 406 of them
  changes none of its 121 descriptions. This port reaches the same answers by not attaching
  them, and keeps the ability to render them for a caller that builds a description directly.
- **Descriptions may be identical but arrive in a different order in a listing.** The
  listing is assembled from a separate copy that is brought up to date shortly after a run
  finishes, so a listing read immediately may be incomplete. `not checked` against
  microsoft/graphrag, which has no equivalent listing.
- **Very large or very small numbers inside a description may be written differently.**
  Numbers are written the way Python writes them, including where it switches to exponent
  form. The rule was reproduced and checked at the switching points, but the only numbers
  that occur in the original's own example are small whole ones, so the behaviour beyond
  those is `not checked` against real data.
- **Text that is not plain characters may be counted differently.** Both sides count size
  using the same scheme, and 176 samples drawn from the original's own text — including
  quotes, line breaks, accented letters and picture characters — agree exactly. Text outside
  that sample is `not checked`.

---

## Licence

microsoft/graphrag is MIT licensed, © Microsoft Corporation. This port reimplements the
behaviour without copied source; see `ACKNOWLEDGEMENTS.md`.
