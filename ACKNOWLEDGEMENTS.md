# Acknowledgements

This project is a port of **[microsoft/graphrag](https://github.com/microsoft/graphrag)**.

## Licence and copyright

- microsoft/graphrag is licensed under the **MIT License**, © Microsoft Corporation
  (`graphrag/LICENSE:1-3`). `LICENSE-graphrag` in this repository carries that text for
  attribution.
- **Nothing was copied verbatim.** Every Java file under `src/` was written fresh against
  behaviour read out of, and run against, the Python source. No source text, comments,
  prompts or test fixtures were transcribed. Class and method documentation names the source
  function it reproduces, which is citation rather than copying.
- **No fixture data is redistributed here.** The parity tests read microsoft/graphrag's own
  test fixture — the entities, relationships and covariates under `tests/verbs/data` — from
  a clone sitting outside this repository, together with the answers its own code gave for
  them. Neither the fixture nor those answers are committed here; the scripts that produce
  them live in the harness repository and require the clone.
- **Behaviour is derived throughout**, plainly. The community assembly, the context
  rendering, the trimming and the level-by-level rollup in this port are a direct port of
  the decision procedure in `graphrag/index/operations/cluster_graph.py`,
  `graphrag/index/workflows/create_communities.py`,
  `graphrag/index/operations/summarize_communities/**` and `graphrag/graphs/**`. That is
  what a port is, and it is not something to obscure.
- Because no MIT-licensed text was copied into this repository, nothing here is bound by
  microsoft/graphrag's licence terms — the rule that copied material carries its licence
  with it does not trigger, since nothing was copied.

## Also used

- **Akka** — the runtime and the software development kit this port is built on.
- **jtokkit** (Apache License 2.0) — counts tokens under the same `cl100k_base` encoding
  microsoft/graphrag counts them under. Declared in `pom.xml`; no source from it appears
  here.
