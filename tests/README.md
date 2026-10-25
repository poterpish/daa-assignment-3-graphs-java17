# Own tests

Run every command from the repository root with **JDK 17** and **Python 3.12+** on `PATH`. Python is only the test driver; the submitted algorithms and route helper are Java. No third-party package is required.

## Commands

```sh
# Teacher's unchanged setup and public checks:
python tools/run.py --setup
python tools/run.py --check

# Compile Java and run all own tests, including full published limits:
python tests/run_tests.py

# Quicker development run: handwritten, random, mixed-request and route-helper tests:
python tests/run_tests.py --quick

# Use the supplied JSON-lines launcher to print the 40 handwritten results:
python tools/run.py --input tests/cases.jsonl

# Re-create the exact large inputs and their mathematical expectations when needed:
python tests/run_tests.py --generate-scale .build/scale-inputs

# Print a full output for one generated large input:
python tools/run.py --input .build/scale-inputs/limit-dag-deep-negative-chain.jsonl

# Reproduce the separate, compilation-excluded Kruskal/Prim benchmark:
python tests/run_benchmark.py
```

`tools/run.py --input` prints answers; it does not compare them. `tests/run_tests.py` performs the comparisons explicitly and fails with a nonzero exit status on a failed assertion, compilation failure, process failure, malformed output or timeout. Each Java input batch has a 20-second timeout and the same `-Xmx384m` limit as the supplied launcher. Compilation has its own 60-second limit.

To call only the helper tests after `python tools/run.py --setup` has compiled the solution:

```sh
javac --release 17 -encoding UTF-8 -cp .build/Java -d .build/Java tests/RouteHelperTest.java
java -Xmx384m -cp .build/Java RouteHelperTest
```

## Files and evidence

| File | Purpose |
|---|---|
| `cases.jsonl` | Forty handwritten requests; line numbers correspond to the next three files. |
| `expected.jsonl` | Independently justified expected results. For non-unique answers this is one valid witness, not a required ordering/label choice. |
| `case_notes.json` | Name and mathematical justification for every handwritten expected result. |
| `actual.jsonl` | Exact JSON output lines from the most recent successful handwritten batch. |
| `random_inputs.jsonl` | All 420 generated small differential inputs, reproducible with seed `20261025`. |
| `random_expected.jsonl` | Independent oracle results for the same random inputs in the same order. |
| `random_actual.jsonl` | Exact Java output for those 420 inputs in the mixed batch. |
| `run_tests.py` | Generator, independent small oracles, semantic witness validator and test runner. |
| `RouteHelperTest.java` | Seven direct Java calls to `Algorithms.reconstructRoute`; the helper is not a JSON task. |
| `helper_results.json` | Actual helper results, original selected edge IDs, directions and weights. The long route is recorded as length and first/last entries after validating every entry. |
| `results.json` | Full-run environment, seed, batch counts, elapsed times, checker self-tests, and actual full-limit summaries with input/output SHA-256 hashes. |
| `results_quick.json` | Written only if the optional `--quick` command is run; it does not claim full-limit coverage. |
| `benchmark_results.json` | Separate real Kruskal/Prim measurements; see root `ANALYSIS.md` and `run_benchmark.py`. |

Large inputs are specified exactly by the readable deterministic `scale_cases()` generator. The committed report contains **actual compact output summaries and hashes**, not every large output array. `--generate-scale` materializes every exact input plus its expected-value description; the normal launcher then prints the full actual output. Small and random cases retain their complete input/expected/actual lines. SHA-256 is computed over compact UTF-8 JSON plus a final newline; it records the run, while semantic validation establishes correctness.

The runner only writes a report with `overall: "PASS"` after all checks in that mode finish. The elapsed times in `results.json` are test-batch wall-clock observations, including JVM startup and JSON transport; they are not the Kruskal/Prim benchmark measurements.

## Independent expected answers

Handwritten cases use explicit mathematical arguments in `case_notes.json`. The runner also checks those stored expectations against the small oracles before executing Java:

| Algorithm under test | Independent small oracle |
|---|---|
| DSU | Maintain component labels and relabel the complete small vertex list after each union; count label frequencies. |
| Kruskal and Prim | Compute original components by breadth-first search, enumerate all edge subsets of size `n-components`, keep those spanning every component, and minimize their total weight. This exhaustive oracle does not run Kruskal or Prim. Random inputs have at most 7 vertices and 11 edges. |
| Kosaraju SCC | Floyd-Warshall Boolean transitive closure; vertices share a component exactly when each reaches the other. |
| DFS topological order | Kahn's indegree algorithm checks the entire graph for cycles; a separate validator checks output permutation and edge precedence. |
| DAG shortest paths | Kahn's algorithm checks global acyclicity, then Bellman-Ford edge sweeps compute distances. No topological-distance algorithm is used by the oracle. |

The 420 seeded random requests consist of 60 DSU, 80 Kruskal, 80 Prim, 60 SCC, 60 topological and 80 DAG cases. Kruskal and Prim receive identical 80 small graphs. Random DAG inputs include 60 generated acyclic graphs with shuffled vertex order and 20 general directed graphs, so numeric vertex order is not an assumed topological order.

The 40 handwritten requests and 420 random requests are sent in one process, followed by `ping`, followed by all 460 requests in reverse order: **921 requests in one JVM**. This exposes stale state between tasks, graph sizes and repeated requests.

## Semantic validation and validator self-tests

Different optimal forests, SCC labels, topological orders and shortest-path parents are allowed. A byte-for-byte comparison with `expected.jsonl` would incorrectly reject such answers. The own checker therefore validates:

- **Forests:** original edge-ID bounds and uniqueness, no selected loop or cycle, exactly `n-components` edges, correct sum, connectivity across every original edge, and the independently established minimum cost.
- **SCCs:** a bijection between returned labels and the independently determined partition; the condensation must be the exact set of cross-component pairs with no duplicates or internal edges.
- **Topological orders:** global cycle decision, the required empty order for cycles, a permutation of all vertices otherwise, and strictly increasing position across every edge.
- **DAG witnesses:** global cycle decision, exact shortest distances, source/unreachable sentinels, original incoming edge IDs, direction and distance consistency, and every finite predecessor chain terminating at the source. Chains are checked with memoized state in linear total time.

`witness_self_tests()` deliberately supplies **two alternative valid answers** for each of Kruskal, Prim, SCC, topo and DAG, and also supplies invalid witnesses. It accepts all 10 valid alternatives and rejects all 14 invalid examples. Invalid examples include repeated/out-of-range forest edges, excess forest edges, missing/duplicate condensation pairs, merged SCCs, wrong precedence, repeated vertices, incorrect incoming parent IDs and a parent for an unreachable vertex. Inputs and supplied answers are recorded in `results.json`; passing Java output alone is not used as evidence that the checker works.

## Boundaries and published limits

The handwritten cases cover empty allowed graphs; singletons; isolates; repeated and self-unions; disconnected forests; negative and tied edges; self-loops; parallel edges; positive and negative totals beyond 32-bit range; arbitrary SCC labels; duplicate condensation edges; DFS edges to already finished vertices; nonzero sources; finite distance `-1`; unreachable negative components; and cycles/self-loops unreachable from the source.

The full run also executes these **11 generated cases**, each with `n=100000` and `m=250000` or `q=250000`:

| Cases | Construction and independent expectation |
|---|---|
| DSU | A chain of `n-1` unions, then repeats/self-unions. After chain union `i` (zero-based), components are `n-i-1` and largest is `i+2`; afterward `[1,n]`. |
| Kruskal + Prim, connected positive | A chain on every vertex, parallel edges of weight `10^9`, and loops. Every consecutive pair must be connected, so cost is `(n-1)*10^9 = 99999000000000`, with one component. |
| Kruskal + Prim, disconnected negative | A negative chain on the first 50000 vertices, positive parallel edges and loops; the other 50000 vertices are isolated. Cost is `-49999000000000`, with 50001 components. This forces Prim to restart across many isolates. |
| SCC pairs | A long forward chain with a reverse edge inside each pair `(2k,2k+1)`. Exactly 50000 SCCs; condensation is a forward chain of 49999 distinct pairs. |
| SCC single cycle | One directed cycle through all vertices, padded by loops. Exactly one SCC and no condensation edges. |
| Topo deep chain | A chain spanning every vertex, padded with duplicate forward edges. The unique order is `0..99999`. |
| DAG deep negative chain | A chain with weight `-10^9` on each original edge, padded with positive parallel edges. Distance to `i` is `-i*10^9`; every parent witness must be valid. |
| Topo disconnected cycle | A long acyclic chain plus a separate two-vertex directed cycle; reject the whole graph. |
| DAG unreachable cycle | The same disconnected cycle is unreachable from source 0; return `acyclic=false` and both arrays empty. |

Every large case is followed, in the **same JVM**, by `ping`, a two-vertex DSU request, an empty topological request and a singleton DAG request; all four answers are validated. No raised stack limit is used.

## Route reconstruction

`RouteHelperTest.java` directly invokes

```java
Algorithms.reconstructRoute(source, target, edges, parentEdge)
```

It covers a reachable route `[0,1,2,3]`, `[source]`, an unreachable target returning `[]`, a tied route actually selected by `dag`, an alternative witness using original parallel edge ID 1, another tied path, and a 100000-vertex route. For every reachable route it checks every selected original ID, edge direction, endpoints and total weight. The long path also checks every vertex position. Expected routes and actual selected IDs are documented in `helper_results.json` and root `ANALYSIS.md`.

Public checks and own tests provide reproducible evidence of these cases. They do not themselves assign or upload the practical grade.
