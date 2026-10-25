# Assignment 3: Graph Algorithms - Java 17

## 1. Scope, interface and reproducibility

The selected language is **Java**, as recorded in `submission.json`. The six
required algorithms and the route-reconstruction helper are implemented in
`src/Java/Algorithms.java`. The supplied `Main.java`, `Input.java`, `Json.java`,
public checker, public cases and Public checks workflow are unchanged. No graph
library or downloaded dependency is used. Standard Java arrays, collections,
sorting and a priority queue support the implementation.

The contract is the assignment [README](README.md) and the supplied
[English assignment](docs/Assignment_3_EN.pdf), based on template commit
[`76c1d91daa90ceadcb225f5408d54d4bfcf397f9`](https://github.com/RZhaniya/daa-assignment-3-graphs/commit/76c1d91daa90ceadcb225f5408d54d4bfcf397f9).
Vertices are `0..n-1`; edge ID `i` always identifies the original input row
`edges[i]`. Results use the supplied UTF-8 JSON Lines transport: exactly one
result per request, no diagnostic text on standard output. `ping` remains
`{"status":"ready"}`. Diagnostics produced by the test scripts are separate
from the solution's JSON protocol.

### Build, run and test commands

Requirements: a **JDK 17** (including `javac`) and **Python 3.12+** for the
provided launcher and tests. Run all commands from the repository root.
Python runs the checking scripts; the submitted algorithms run in Java.

```sh
java -version
javac -version
python --version
python tools/run.py --setup
python tools/run.py --check
python tests/run_tests.py
python tools/run.py --input tests/cases.jsonl
python tests/run_benchmark.py
```

The own-test runner automatically compiles Java and compares results. The
provided `--input` mode only prints results; it does not check them. On Windows,
`py -3` can replace `python`; on systems where Python 3 is named `python3`, use
`python3` instead. The documented commands require no third-party Python package.

Equivalent direct Java commands on a POSIX shell are:

```sh
mkdir -p .build/Java
javac --release 17 -encoding UTF-8 -d .build/Java src/Java/*.java
java -Xmx384m -cp .build/Java Main < tests/cases.jsonl
javac --release 17 -encoding UTF-8 -cp .build/Java -d .build/Java tests/RouteHelperTest.java
java -Xmx384m -cp .build/Java RouteHelperTest
```

The provided runner limits compilation to 60 seconds, each request batch to 20
seconds, and the Java heap to 384 MiB. Full-scale own cases exercise the same
heap limit. GitHub's original **Public checks** runs the official local checking
commands after a push to a personal copy. The added **Student tests** workflow
also runs the own-test suite. Local pass results do not claim that a remote
workflow has already run or that a grade has been awarded.

## 2. Shared design and limits

All mutable state is local to one method call, including DSU arrays, adjacency
arrays, queues, DFS stacks, distances and selected edges. The only static
members are methods and a nested class definition; there is no persistent
algorithm cache. Consecutive requests may have different tasks and different
vertex counts. `Input.edges`/`Input.operations` creates ordinary integer arrays
for each request. Sorting Kruskal's separate ID array leaves original rows and
their IDs intact.

Directed adjacency lists store original edge IDs in exact-size `int[][]`
arrays. Construction counts degrees, allocates rows, and fills them in input
order, taking `O(n+m)` time and space. An undirected edge is recorded at both
endpoints. Each traversal retrieves its endpoint from the unchanged original
row. This also distinguishes parallel edges and retains route witnesses.

No graph traversal uses Java recursion. DFS stores active vertices in an
`int[n]` stack and saves a next-edge index for each vertex. After returning from
a child, traversal resumes at the next unused neighbor. Each vertex is pushed
once per pass and each adjacency entry is examined once. A 100,000-vertex
chain therefore uses heap arrays rather than 100,000 Java call frames, and
neighbor lists are never repeatedly scanned from the start.

Weights fit `int`, but all costs, candidate distances and finite distances use
`long` or `Long`. A simple path or forest has at most `n-1` edges, so the
largest required absolute sum is at most
`99,999 * 1,000,000,000 = 99,999,000,000,000`, safely inside signed 64-bit range.
Comparators compare integer weights directly rather than subtracting them.
Only the explicitly documented hash mixing uses intentional wrapping integer
arithmetic. Unreachable distance is `null`; finite `-1` remains a valid answer.

In the bounds below, `n` is vertices, `m` edges, `q` union operations, `c` input
connected components, and `L` the number of vertices in a reconstructed route.
Memory bounds include results and input conversion, not just working arrays.

| Method | Time | Space including result |
|---|---|---|
| DSU | `O(n + q alpha(n))` amortized | `O(n+q)` |
| Kruskal | `O(n + m log(m+1))` | `O(n+m)` |
| Prim, lazy heap | `O(n + m log(m+1))` | `O(n+m)` |
| Kosaraju SCC | Expected `O(n+m)` with hash deduplication | `O(n+m)` |
| DFS topological ordering | `O(n+m)` | `O(n+m)` |
| DAG shortest paths | `O(n+m)` | `O(n+m)` |
| Route reconstruction | `O(L)` for a reachable route; `O(1)` for no route | `O(L)` |

## 3. Disjoint-set union

### Steps and invariant

Initially each vertex is its own root with size one; the component count is
`n`, and the largest size is one when `n>0`, otherwise zero. `find` first walks
to the root, then performs a second iterative walk that points every visited
vertex directly to that root. `union` finds both roots, returns without changing
counts if they coincide, and otherwise attaches the smaller root below the
larger root. It updates the surviving root's size, decrements the component
count, and updates the largest component size.

The invariant is that parent trees form exactly the connectivity partition of
all operations processed so far. Root sizes equal actual numbers of vertices;
the stored count and maximum describe that partition. After every operation,
the implementation appends `[components,largest]`, without an initial state.

### Correctness

The invariant holds for the initial singleton partition. Path compression
changes only a route to the same root, preserving component membership. A
union within one component changes nothing. A union between two components
creates exactly the newly connected component and sums its sizes; all other
components remain unchanged. Induction on the operation sequence proves every
returned state. Union by size chooses the efficient attachment direction
without changing which vertices become connected.

### Boundaries and complexity

Self-unions and repeated unions preserve both statistics. For `n=0`, valid
input has no operations and the result is `states=[]`. Isolated vertices count
as singleton components. Initialization is linear, and union by size plus
path compression gives amortized `alpha(n)` work per union. The arrays use
`O(n)` space and `q` returned pairs use `O(q)`.

## 4. Kruskal minimum spanning forest

### Steps and invariant

Create IDs `0..m-1`, sort them by `(weight, original ID)`, initialize a fresh
DSU, and process the sorted IDs. Select an edge only when its endpoints are in
different DSU components. Add its weight to a `long` total and keep its original
ID. The final DSU count is returned as `components`.

The selected edges always form a forest. They can be extended to a minimum
spanning forest of the input, and DSU components equal connected components of
the selected forest.

### Correctness

An accepted edge joins distinct trees, so it cannot create a cycle. Consider
the cut consisting of one current tree and the remaining vertices. When the
algorithm selects a crossing edge, any strictly lighter crossing edge would
already have been processed and joined those components. Thus the selected
edge is a light edge for this cut. By the cut/exchange property it can be
included in an optimum forest extending the previously selected edges.
Induction preserves optimal extendibility.

After all edges have been examined, no original edge joins different remaining
DSU components. Therefore each original connected component has been spanned,
and the selected optimum forest has exactly `n-c` edges. The accumulated sum
is the weight of those exact original rows.

### Boundaries and complexity

Loops have identical roots and cannot be selected, even with negative weight.
Parallel and tied edges remain distinct IDs; deterministic ID tie-breaking
chooses one valid optimum. Negative weights need no special algorithm change.
The `n` singleton DSU initialization includes isolates. Empty graphs return
cost zero, no IDs and zero components.

Sorting costs `O(m log(m+1))`; initialization and the DSU scan fit the stated
`O(n + m log(m+1))` bound. ID sorting, input rows, DSU and the returned forest
require `O(n+m)` space.

## 5. Prim minimum spanning forest

### Steps and invariant

Build undirected adjacency by original edge ID and keep a visited array. For
each unvisited root, increment the component count, mark the root visited and
put incident edges to unvisited endpoints into a minimum priority queue.
Repeatedly remove the lightest ID. If both endpoints are visited, discard the
stale entry. Otherwise select the edge and visit its unvisited endpoint,
adding the newly exposed crossing edges. Restart after the heap empties.

Within one component, selected edges form a tree spanning the visited vertices.
The heap contains every edge crossing from those visited vertices to unvisited
ones, possibly along with stale internal edges. An edge enters the heap when
its first endpoint is visited; its other endpoint is then unvisited, so an
entry never has two unvisited endpoints.

### Correctness

After stale entries are removed, the heap minimum is a lightest edge crossing
the current visited/unvisited cut. The cut property makes that edge safe for
a minimum spanning tree of this component. Its unvisited endpoint makes the
tree grow by one vertex without a cycle. Heap exhaustion means no edge reaches
an unvisited vertex of that component, so the whole component is spanned.
Restarting applies the same proof independently to every component. Summing
their minimum trees gives a minimum spanning forest.

### Boundaries and complexity

An isolated root increments the count even though its heap is empty. A loop
has an already visited other endpoint and never enters the heap. Negative
weights, ties and parallel edges work under the same ordering. Empty graphs
have no roots and produce the required zero result.

Adjacency construction and root scanning cost `O(n+m)`. Each edge is inserted
at most once and removed at most once; lazy heap size is `O(m)`. Consequently
time is `O(n + m log(m+1))`, and adjacency, heap, visited and results use
`O(n+m)` space. This is a separate implementation: it does not call Kruskal
or use Kruskal's sorted scan.

## 6. Kosaraju strongly connected components

### Steps and invariant

Build outgoing and transposed adjacency. Run iterative DFS on the original
graph, ignoring cycle rejection and recording each vertex when it finishes.
Process vertices in decreasing finish order. For each unlabelled vertex, run
an iterative DFS on the transpose and assign one new component label to all
vertices reached. Finally scan the original edges and emit each distinct
cross-label pair once.

In the first pass, finishing order is exactly the order of a conventional DFS
with the same adjacency order. In the second pass, already assigned groups
are complete SCCs, and the next DFS assigns one complete remaining SCC.

### Correctness

Contract the original graph into its SCC DAG. For an edge from one SCC `C` to
a different SCC `D`, the maximum first-pass finish time of `C` is greater than
that of `D`. This follows whether DFS first enters `C` and reaches `D`, or first
finishes `D` without being able to reach `C`. Thus the remaining SCC with
largest finish time has no incoming edge from another unassigned SCC.

In the transpose this SCC has no outgoing edge to another unassigned SCC.
The second DFS cannot escape it, while strong connectivity ensures that the
DFS reaches every vertex inside it. Induction proves that labels identify
exactly the SCC partition. Scanning all original edges then includes every
required cross-component pair; excluding equal labels removes internal edges
and the set removes duplicates.

### Boundaries, hashing and complexity

Isolates each receive a separate label. Self-loops and repeated directed edges
do not change the partition. For `n=0`, both arrays are empty. Labels are
nonnegative consecutive integers here, although the contract allows other
valid labelings. Condensation order is not significant.

A pair is packed into a unique 64-bit key. `mixPair` applies the standard
Stafford Mix13/SplitMix64 finalizer before insertion into `HashSet<Long>` to
avoid poor `Long` hashes for consecutive labels. Multiplication by odd
constants and xor-right-shift steps are bijective on 64-bit words, so mixing
cannot make two different packed pairs equal. Its arithmetic wraps by design.
Attribution is given in Section 11. No randomization or graph-library routine
is used to compute SCCs.

Both DFS passes, construction and the final scan are linear. Hash operations
give **expected** `O(n+m)` total time, with `O(n+m)` space. The expected-time
qualification concerns the hash table; the DFS work itself is worst-case
linear. No sorting of condensation pairs is required.

## 7. DFS topological ordering

### Steps and invariant

Each vertex is unvisited (`0`), active (`1`) or finished (`2`). Every unvisited
root starts a DFS. A frame remembers its next outgoing edge. An edge to an
unvisited vertex pushes that vertex; an edge to an active vertex immediately
returns a cyclic result. Edges to finished vertices are allowed. When a frame
has no remaining edges, mark its vertex finished and place it at the next
position from the end of the output array.

Active vertices are exactly the current DFS path. A finished vertex has had
every outgoing edge examined. Each finished vertex appears exactly once in
the reverse-finish array.

### Correctness

An edge to an active vertex closes a path back to an ancestor and therefore
forms a directed cycle. Conversely, DFS on a graph containing a cycle must
encounter a back edge before all vertices of that cycle can finish. Checking
every root therefore detects cycles in every component.

If no back edge exists, for every edge `u -> v`, vertex `v` finishes before
`u`: it either finishes in `u`'s traversal or was already finished when the
edge was inspected. Reversing finish order places `u` before `v`. The output
contains every vertex exactly once and satisfies all directed precedence
constraints.

### Boundaries and complexity

A self-loop immediately reaches an active vertex. Duplicate edges are safe;
cross/forward edges to finished vertices are not falsely called cycles.
Disconnected graphs are fully checked. A cycle yields `acyclic=false` with
an empty order, even if some components have already finished. Empty graphs
yield `acyclic=true` and `[]`. Each vertex and edge is processed a constant
number of times, so time and space are `O(n+m)`.

## 8. DAG shortest paths and route reconstruction

### Steps and invariant

Run the same three-state topological DFS over the **whole graph**, before
performing any relaxation. If any component is cyclic, return
`acyclic=false`, `distance=[]`, `parent_edge=[]`. Otherwise initialize every
distance to `null` and every parent to `-1`, except `distance[source]=0`.
Process the topological order; skip unreachable vertices and relax each
reachable vertex's outgoing edges with `long` arithmetic. On a strict
improvement, store both the new distance and that edge's original ID. Ties
keep the previously selected equally optimal parent.

When a vertex is processed, its distance is already the minimum cost of all
source-to-vertex paths. Every finite nonsource distance has a matching incoming
parent edge from an earlier vertex in the topological order.

### Correctness

In a DAG, every predecessor of `v` appears before `v`. Inductively, when `v`
is processed, all reachable predecessors already have correct shortest
distances and all incoming possibilities have been relaxed. Their minimum is
therefore the shortest distance to `v`, even for negative weights. If none is
reachable, there is no source path and `v` remains `null`. The source cannot
be improved through a reachable predecessor without creating a cycle back to
the source, so its distance remains zero and its parent remains `-1`.

A selected parent ends at its child and satisfies
`distance[child] = distance[parent] + weight`. Following parents strictly
decreases topological position. Hence parent chains cannot cycle and must end
at the source for reachable vertices, giving valid shortest-path witnesses.

Checking all components before relaxation is necessary: a cycle unreachable
from the source still makes the requested graph non-DAG. A reachability-only
cycle check would violate the assignment.

### Helper contract and complexity

The helper is directly callable Java code, not another JSON task:

```java
static List<Integer> reconstructRoute(
    int source, int target, int[][] edges, int[] parentEdge)
```

Use the original edge rows and the parent array from a successful acyclic DAG
result. The helper returns `[source]` for the source and an empty list for an
unreachable target. Otherwise it follows original incoming edge IDs backward
until the source, records the vertices and reverses the list. Defensive checks
reject invalid edge IDs/directions, out-of-range vertices and cyclic parent
chains. It does not copy or scan the whole graph for a short route.

Each of `L` route vertices is processed once and the final reversal is linear,
so valid reconstruction takes `O(L)` time and output space. A no-route lookup
is constant time. Topological checking plus relaxation scans the graph twice,
so the DAG method itself uses `O(n+m)` time and space. Parallel edges retain
their own IDs, `null` cannot collide with a negative distance, and sums beyond
32 bits are supported.

Actual helper calls, own-test evidence and benchmark measurements follow in
the remaining sections.

## 9. Tests, expected answers and actual results

### Evidence and independent checking

The full commands in Section 1 were actually executed on 2026-10-25. The
provided setup check passed; all **36 public cases** and the additional
**two complete batches in one process** passed. The unchanged checker log is
[`tests/public_checks.log`](tests/public_checks.log), with setup recorded in
[`tests/setup.log`](tests/setup.log). The checking-tool regression suite also
completed successfully: 16 discovered tests, 14 passed and two .NET-specific
tests skipped because the .NET SDK is absent in the local environment. The
Java transport tests did run. Details are in
[`tests/public_tools.log`](tests/public_tools.log).

Own inputs, justified expectations and exact observed outputs are stored in
`tests/cases.jsonl`, `tests/expected.jsonl`, `tests/case_notes.json` and
`tests/actual.jsonl`. Expected results were not obtained by copying this
solution's output. The handwritten justifications use direct connectivity,
cut, path and reachability arguments. For non-unique results, the expected
file contains one valid witness, and the checker accepts all valid witnesses.

Small differential testing uses independent algorithms:

| Method tested | Independent oracle |
|---|---|
| DSU | Relabel a complete small component-label array and count frequencies. |
| Kruskal and Prim | Find original components by BFS, enumerate all subsets of `n-c` edge IDs, retain spanning forests and take minimum weight. |
| SCC | Boolean transitive closure; mutual reachability determines the partition. |
| Topological ordering | Kahn's indegree algorithm detects global cycles; precedence validates the returned order. |
| DAG distances | Kahn checks global acyclicity; Bellman-Ford edge sweeps calculate shortest distances. |

The random seed is `20261025`. There are 60 DSU, 80 Kruskal, 80 Prim, 60 SCC,
60 topo and 80 DAG requests: **420** seeded random test cases. Kruskal
and Prim receive the same 80 small weighted graphs. Exhaustive forest tests
have at most seven vertices and eleven edges. Generated DAGs shuffle vertex
labels, so numerical vertex order cannot accidentally substitute for
topological order. The full random input, expected and actual files are
`tests/random_inputs.jsonl`, `tests/random_expected.jsonl` and
`tests/random_actual.jsonl`.

Witness validation independently checks original IDs, selected-edge
uniqueness, forest acyclicity and spanning, exact sums and optimum costs,
SCC partitions and exact condensation, topological permutations and
precedence, and shortest distances with valid incoming parent paths. It also
checks that every reachable parent chain reaches the source. A memoized
parent traversal makes this linear over all vertices.

The checker itself is tested with alternate valid and invalid witnesses:
different optimal trees, renamed SCC labels, different topological orders,
and tied/parallel shortest-path parents. The recorded checks accept **10**
valid alternatives and reject **14** invalid witnesses. This guards against
both a checker that incorrectly demands one arbitrary ordering and a checker
that accepts a correct numeric cost with invalid evidence.

### Measured own-test outcome

The completed run is recorded in [`tests/results.json`](tests/results.json):

| Test group | Actual result |
|---|---|
| Handwritten cases with individual justifications | 40 passed |
| Seeded differential requests | 420 passed |
| Mixed persistent-process batch | 921 requests passed in one JVM |
| Direct route-helper calls | 7 passed |
| Full-limit generated cases | 11 passed |
| Post-large-request reset checks | 44 passed |
| Valid witness alternatives / invalid witness examples | 10 accepted / 14 rejected |

The 921-request batch contains all 40 handwritten and 420 random cases,
then `ping`, then the same 460 cases in reverse order. It is intentionally
not counted as 921 different graph instances. Every full-limit case is
followed in the same JVM by four small requests: ping, DSU, empty topo and
singleton DAG. These checks exercise state after large allocations and
after different algorithm types.

All eleven scale cases have **100,000 vertices** and **250,000 edges or
union operations**. The deterministic `scale_cases()` generator specifies
the exact inputs and mathematical expected values. It covers:

| Full-limit construction | Expected property |
|---|---|
| DSU chain followed by repeats/self-unions | Final `[components,largest]=[1,100000]`; every intermediate state checked. |
| Positive connected chain, parallels and loops, each MST method | Cost `99999000000000`, 99,999 edges, one component. |
| Negative chain on half the vertices, positive parallels, loops and isolates, each MST method | Cost `-49999000000000`, 49,999 edges, 50,001 components. |
| SCC chain of bidirectional pairs | 50,000 SCCs; exactly 49,999 condensation pairs. |
| SCC cycle through all vertices | One SCC, empty condensation. |
| Topological deep chain with duplicate forward edges | Unique order `0..99999`. |
| DAG deep negative chain with positive parallel edges | `distance[i]=-i*1000000000`; valid original parent IDs. |
| Topological graph with a separate two-vertex cycle | Reject the whole graph. |
| DAG with a cycle unreachable from source 0 | Reject; distance and parent arrays empty. |

The large cases passed with the supplied **384 MiB Java heap** and **20-second
batch timeout**, without increasing the stack limit. Actual batch durations,
including JVM startup, JSON transport and four reset requests, ranged from
**0.343 to 0.654 seconds** in this environment. Those observations are test
run times, not the algorithm benchmark below, and are not a promised runtime
on another machine.

Large outputs are retained as actual compact summaries and SHA-256 digests
in `results.json`; every returned array element was validated before recording
the summary. To materialize the exact large inputs and expected descriptions:

```sh
python tests/run_tests.py --generate-scale .build/scale-inputs
python tools/run.py --input .build/scale-inputs/limit-dag-deep-negative-chain.jsonl
```

The handwritten tests additionally cover empty allowed graphs, singleton and
isolated vertices, negative loops, repeated unions, ties, parallel edges,
positive and negative totals beyond 32 bits, finite `-1`, nonzero sources,
unreachable negative components, self-loops and edges to already finished
DFS vertices. [The test README](tests/README.md) maps files, commands and
expected-value reasoning in more detail.

### Actual route-helper demonstrations

These are direct calls executed by `tests/RouteHelperTest.java`, with actual
records in [`tests/helper_results.json`](tests/helper_results.json).
For the first graph:

```java
int[][] edges = {{0,1,3}, {0,2,8}, {1,2,-7}, {2,3,4}};
// n=5, source=0; obtained by calling Algorithms.dag:
Long[] distance = {0L, 3L, -4L, 0L, null};
int[] parentEdge = {-1, 0, 2, 3, -1};

Algorithms.reconstructRoute(0, 3, edges, parentEdge); // [0,1,2,3]
Algorithms.reconstructRoute(0, 0, edges, parentEdge); // [0]
Algorithms.reconstructRoute(0, 4, edges, parentEdge); // []
```

The reachable route selects IDs `[0,2,3]`: `0->1` with weight 3, `1->2`
with weight -7, and `2->3` with weight 4. Their sum is zero, equal to
`distance[3]`. The source route selects no edges and has cost zero. The
unreachable route selects no edges and has no finite cost; its JSON distance
remains `null`, not zero.

For tied routes and distinct parallel edges:

```java
int[][] tied = {{0,1,2}, {0,1,2}, {0,2,1}, {1,3,1}, {2,3,2}};
// Actual parent array from a DAG request with n=5 and source=0:
int[] selectedByDag = {-1, 0, 2, 4, -1};
Algorithms.reconstructRoute(0, 3, tied, selectedByDag); // [0,2,3]

int[] parallelAlternative = {-1, 1, 2, 3, -1};
Algorithms.reconstructRoute(0, 3, tied, parallelAlternative); // [0,1,3]

int[] tiedAlternative = {-1, 0, 2, 4, -1};
Algorithms.reconstructRoute(0, 3, tied, tiedAlternative); // [0,2,3]
```

The actually selected route uses IDs `[2,4]`, with cost `1+2=3`. The
parallel-edge alternative uses IDs `[1,3]`, with cost `2+1=3`; edge ID 1 is
deliberately different from the equally weighted parallel edge ID 0. Both
routes follow the stored original edge directions. The direct helper test
also reconstructs all 100,000 vertices of a chain, validates every vertex
and ID, and obtains cost `99999000000000`.

## 10. Kruskal-Prim benchmark on identical graphs

### Inputs, correctness and timing boundary

[`tests/Benchmark.java`](tests/Benchmark.java) generates three connected
weighted multigraphs. For size `n`, the seed is `20261025+n` using
`java.util.Random`, and `m=4n`. First generate a chain joining all vertices,
then random endpoint pairs until there are exactly `m` edges. Every weight,
including each chain weight, is drawn uniformly from the inclusive integer
range `[-1000000000,1000000000]`. Fisher-Yates shuffling randomizes input edge
order. Extra edges may be loops or parallel edges. Both methods receive the
**same preconstructed request and same original edge order** for each size.

Before timing, both returned forests are independently certified: original
IDs must be valid/distinct; selected edges must be acyclic and span the input
components; the count, `n-c` edge total and reported cost must agree with the
witness. Optimality is checked using a separate cycle-property certificate.
For every original nonloop edge `(u,v,w)`, `w` must be at least the maximum
edge weight on the selected tree path from `u` to `v`. Otherwise replacing
that heavier tree edge would improve the forest. Conversely, satisfying
this inequality for all original edges certifies that no improving exchange
is possible and the forest is minimum. Binary lifting checks path maxima;
its maxima start at `Long.MIN_VALUE` so negative weights work correctly.
This validation is outside all measured intervals and does not assume that
agreement between the two implementations proves optimality.

The generator is reproducible from source and seeds. `benchmark_results.json`
also records each graph's SHA-256 over `n,m` followed by all `(u,v,w)` rows,
encoded as signed 32-bit big-endian integers. All three hashes and both
methods' raw samples are included in that file.

Timing uses elapsed `System.nanoTime()` differences around **one complete
`Algorithms.kruskal(request)` or `Algorithms.prim(request)` call**. Thus it
includes `Input.edges` conversion, method-specific arrays/DSU/adjacency/heap,
sorting, algorithm execution and result allocation. It excludes graph
generation, boxed request construction, JSON parsing/serialization,
validation, output, JVM startup and compilation. The boundary is identical
for both methods. Returned costs are consumed after timing.

For each size, there is one untimed verification call per method, followed
by **eight explicit warm-up calls per method** and **seven measured calls
per method**. Kruskal-first and Prim-first pairs alternate during warm-up
and measurement. Sizes run in increasing order in one JVM. No sample is
discarded. The median is the fourth value of seven sorted elapsed samples.

### Environment and reproduction

Measurements were collected on **2026-10-25**, using Ubuntu 24.04.3,
Linux `6.18.55`, `amd64`, OpenJDK **17.0.20+8-1-24.04-Ubuntu**, OpenJDK 64-Bit
Server VM, and Python **3.12.14** as the driver. CPU model: **AMD EPYC 9V74
80-Core Processor**; nine logical CPUs were visible/allowed to the container,
with an aggregate CPU quota of eight cores (`800000/100000`). The container
memory limit was 8 GiB; the benchmark JVM used `-Xms128m -Xmx384m` and the
algorithms themselves were single-threaded. The preparation environment
needed launcher wrappers to expose its installed JDK; they do not change
the source, compiler release, algorithms or timer and are not required on
a normal JDK installation.

```sh
python tests/run_benchmark.py
```

The driver compiles first, then starts the benchmark JVM. Equivalent commands:

```sh
mkdir -p .build/Benchmark
javac --release 17 -encoding UTF-8 -d .build/Benchmark src/Java/*.java tests/Benchmark.java
java -Xms128m -Xmx384m -cp .build/Benchmark Benchmark
```

For the recorded preparation run, the wrapper was exposed on `PATH`, and
the driver received `--host-info` pointing to actual CPU/container metadata
read from the same host. A normal rerun reads those details directly when
available. Both exact environment data and raw nanosecond values are in
[`tests/benchmark_results.json`](tests/benchmark_results.json). Rerunning
regenerates that measurement file; update the table below when submitting
new measurements rather than mixing results from different runs.

### Actual samples

All values below are **milliseconds**. Rounding is for display only; medians
were calculated from the original nanosecond samples.

| n | m | Seed | Method | Seven elapsed times (ms) | Median (ms) |
|---:|---:|---:|---|---|---:|
| 1,000 | 4,000 | 20262025 | Kruskal | 1.883, 1.613, 3.104, 3.076, 3.236, 3.978, 3.889 | **3.104** |
| 1,000 | 4,000 | 20262025 | Prim | 2.128, 1.973, 2.261, 2.236, 1.970, 1.979, 1.939 | **1.979** |
| 10,000 | 40,000 | 20271025 | Kruskal | 12.946, 13.467, 16.097, 15.503, 13.730, 14.522, 13.400 | **13.730** |
| 10,000 | 40,000 | 20271025 | Prim | 26.071, 24.456, 29.903, 26.613, 26.181, 30.329, 24.179 | **26.181** |
| 50,000 | 200,000 | 20311025 | Kruskal | 143.218, 114.370, 160.973, 123.210, 110.785, 132.965, 133.835 | **132.965** |
| 50,000 | 200,000 | 20311025 | Prim | 335.983, 263.909, 299.777, 259.940, 237.618, 299.646, 344.832 | **299.646** |

The independently certified optimum costs, shared by both methods, are
`-718277834591`, `-7109946578125` and `-35411340579835`, respectively.
All graphs have one connected component and each selected tree has `n-1`
edges.

### Observations, interpretation and limitations

Prim has the lower median at the smallest size in this run. At 10,000 and
50,000 vertices, Kruskal has the lower median; Prim/Kruskal median ratios are
approximately 1.91 and 2.25. This is compatible with implementation constants:
Kruskal sorts once and performs inexpensive DSU scans, while this lazy Prim
constructs adjacency, allocates heap entries and later removes stale entries.
That explanation is a plausible interpretation, not a separately measured
breakdown of sorting, allocation and heap costs.

Both methods have the same stated `O(n + m log(m+1))` asymptotic upper bound
here. Three finite sizes do not establish a complexity theorem. One seeded
sparse connected graph per size also does not represent every density,
topology or weight distribution. The small-size reversal and visible sample
spread caution against claiming one method is always faster.

This is an ordinary in-process educational benchmark, not JMH with multiple
independent JVM forks. JIT compilation may continue after warm-up; allocation,
garbage collection, cache state and shared-host scheduling can affect results.
Alternating method order and reporting every sample plus the median reduce
some bias, but do not eliminate it. The use of `nanoTime` gives a nanosecond
unit, not a claim of nanosecond measurement accuracy. JSON transport and
external I/O are excluded, so these are not end-to-end request latencies.

## 11. Sources, AI assistance and submission responsibility

Sources used in preparing this implementation and report:

1. The [assignment template](https://github.com/RZhaniya/daa-assignment-3-graphs)
   and its pinned base commit listed in Section 1: algorithm requirements,
   JSON contracts, limits, rubric, supplied adapters, public tests and
   submission instructions.
2. The supplied `docs/Assignment_3_EN.pdf` and `tests/README.md`: mandatory own
   testing, route-helper demonstrations, English analysis and benchmark.
3. [Oracle Java 17 `System.nanoTime` documentation](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/System.html#nanoTime()):
   elapsed-time measurement and precision/resolution limitations.
4. David Stafford, [Better Bit Mixing - Improving on MurmurHash3's 64-bit
   Finalizer](https://zimbry.blogspot.com/2011/09/better-bit-mixing-improving-on.html),
   Mix13 parameters, also used in OpenJDK's `SplittableRandom`: the small
   reversible hash-key mixing formula in `mixPair`. This helper improves
   condensation-key distribution; it is not a graph algorithm.

**AI assistance disclosure.** OpenAI ChatGPT assisted with implementing the
Java algorithms, designing independent test oracles and semantic validators,
writing the route-helper tests and benchmark, reviewing correctness and
complexity, and preparing this report. The code and test data in this
submission were prepared with that assistance; this is not a claim that the
repository owner wrote them without assistance. Standard graph algorithms
are implemented directly, without calling a graph library.

**Verification actually performed.** During preparation, the source was
reviewed against the contracts, compiled with Java release 17, run through
the unchanged setup and public checks, and exercised with the recorded own
tests and benchmark. Stored actual output and timing files came from those
runs. The report does not attribute an unperformed local review or test run
to the repository owner. The owner should read and understand the code,
rerun the commands as appropriate, and accurately describe any further
personal verification when submitting or defending the work.

The linked Moodle syllabus could not be retrieved in this environment, and
the exact Moodle deadline was not visible. The repository owner must check
the course's applicable AI/integrity rules, deadline and approved extensions;
this disclosure does not substitute for those policies. No private-repository
invitation, GitHub push or Moodle submission has been made by this local
preparation. The exact final commit URL must be taken from the owner's
repository after upload, placed in Moodle Online text, and followed by
**Submit assignment**. Public checks report pass/fail only; they do not award
the practical mark. The separate quiz is outside this code submission.
