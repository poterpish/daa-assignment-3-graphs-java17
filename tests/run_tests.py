#!/usr/bin/env python3
"""Independent, reproducible tests for the Java graph assignment (stdlib only)."""
from __future__ import annotations

import argparse
import collections
import hashlib
import itertools
import json
import pathlib
import platform
import random
import subprocess
import sys
import time

ROOT = pathlib.Path(__file__).resolve().parents[1]
HERE = ROOT / "tests"
sys.path.insert(0, str(ROOT / "tools"))
from run import build  # Supplied compiler/launcher; no teacher validators are imported.

SEED = 20261025


def integer(value):
    return type(value) is int and -(1 << 63) <= value < (1 << 63)


def ints(values):
    return isinstance(values, list) and all(integer(value) for value in values)


def kahn(n, edges):
    """Independent cycle oracle: indegrees, not three-state DFS."""
    adjacency = [[] for _ in range(n)]
    degree = [0] * n
    for edge in edges:
        u, v = edge[:2]
        adjacency[u].append(v)
        degree[v] += 1
    queue = collections.deque(v for v in range(n) if degree[v] == 0)
    order = []
    while queue:
        u = queue.popleft()
        order.append(u)
        for v in adjacency[u]:
            degree[v] -= 1
            if degree[v] == 0:
                queue.append(v)
    return order if len(order) == n else None


def undirected_partition(n, edges):
    """Small-case connectivity by breadth-first search."""
    adjacency = [[] for _ in range(n)]
    for u, v, *_ in edges:
        adjacency[u].append(v)
        adjacency[v].append(u)
    labels = [-1] * n
    count = 0
    for start in range(n):
        if labels[start] != -1:
            continue
        labels[start] = count
        queue = collections.deque([start])
        while queue:
            for vertex in adjacency[queue.popleft()]:
                if labels[vertex] == -1:
                    labels[vertex] = count
                    queue.append(vertex)
        count += 1
    return labels, count


def oracle(request):
    """Small inputs only: deliberately different algorithms from the solution."""
    task = request["task"]
    if task == "ping":
        return {"status": "ready"}
    n = request["n"]
    if task == "dsu":
        labels = list(range(n))
        states = []
        for u, v in request["operations"]:
            old, new = labels[v], labels[u]
            labels = [new if label == old else label for label in labels]
            sizes = collections.Counter(labels)
            states.append([len(sizes), max(sizes.values(), default=0)])
        return {"states": states}
    edges = request["edges"]
    if task in ("kruskal", "prim"):
        _, components = undirected_partition(n, edges)
        best_cost, best_ids = None, None
        # Enumerate every possible forest-sized edge subset. This is not Kruskal/Prim.
        for ids in itertools.combinations(range(len(edges)), n - components):
            selected = [edges[i] for i in ids]
            _, selected_components = undirected_partition(n, selected)
            if selected_components != components:
                continue
            cost = sum(edge[2] for edge in selected)
            if best_cost is None or cost < best_cost:
                best_cost, best_ids = cost, list(ids)
        assert best_cost is not None
        return {"cost": best_cost, "edge_ids": best_ids, "components": components}
    if task == "scc":
        reachable = [[i == j for j in range(n)] for i in range(n)]
        for u, v in edges:
            reachable[u][v] = True
        for k in range(n):
            for u in range(n):
                for v in range(n):
                    reachable[u][v] |= reachable[u][k] and reachable[k][v]
        labels = [-1] * n
        for u in range(n):
            if labels[u] == -1:
                for v in range(n):
                    if reachable[u][v] and reachable[v][u]:
                        labels[v] = u
        condensation = sorted({(labels[u], labels[v]) for u, v in edges if labels[u] != labels[v]})
        return {"component": labels, "condensation": [list(pair) for pair in condensation]}
    order = kahn(n, edges)
    if task == "topo":
        return {"acyclic": order is not None, "order": order if order is not None else []}
    assert task == "dag"
    if order is None:
        return {"acyclic": False, "distance": [], "parent_edge": []}
    distance = [None] * n
    parents = [-1] * n
    distance[request["source"]] = 0
    # Bellman-Ford edge sweeps, not topological relaxation.
    for _ in range(n - 1):
        changed = False
        for edge_id, (u, v, weight) in enumerate(edges):
            if distance[u] is not None:
                candidate = distance[u] + weight
                if distance[v] is None or candidate < distance[v]:
                    distance[v], parents[v], changed = candidate, edge_id, True
        if not changed:
            break
    return {"acyclic": True, "distance": distance, "parent_edge": parents}


def validate(request, answer, expected):
    """Check contracts and witnesses in linear/near-linear time, not exact tie choices."""
    try:
        return _validate(request, answer, expected)
    except (KeyError, TypeError, ValueError, IndexError):
        return False


def _validate(x, y, expected):
    if not isinstance(y, dict):
        return False
    task = x["task"]
    if task == "ping":
        return y == {"status": "ready"}
    n = x["n"]
    if task == "dsu":
        states = y["states"]
        return (isinstance(states, list) and all(ints(s) and len(s) == 2 for s in states)
                and len(states) == len(x["operations"]) and states == expected["states"])
    if task in ("kruskal", "prim"):
        if not integer(y["cost"]) or not integer(y["components"]):
            return False
        if (y["cost"], y["components"]) != (expected["cost"], expected["components"]):
            return False
        ids = y["edge_ids"]
        if not ints(ids) or len(ids) != n - y["components"] or len(ids) != len(set(ids)):
            return False
        parent, size = list(range(n)), [1] * n

        def find(v):
            while parent[v] != v:
                parent[v] = parent[parent[v]]
                v = parent[v]
            return v

        cost = 0
        for edge_id in ids:
            if not 0 <= edge_id < len(x["edges"]):
                return False
            u, v, weight = x["edges"][edge_id]
            a, b = find(u), find(v)
            if a == b:
                return False
            if size[a] < size[b]:
                a, b = b, a
            parent[b] = a
            size[a] += size[b]
            cost += weight
        return cost == y["cost"] and all(find(u) == find(v) for u, v, _ in x["edges"])
    if task == "scc":
        labels = y["component"]
        if not ints(labels) or len(labels) != n or any(label < 0 for label in labels):
            return False
        forward, backward = {}, {}
        for actual, reference in zip(labels, expected["component"]):
            if forward.setdefault(actual, reference) != reference or backward.setdefault(reference, actual) != actual:
                return False
        pairs = y["condensation"]
        if not isinstance(pairs, list) or not all(ints(pair) and len(pair) == 2 for pair in pairs):
            return False
        pair_set = {tuple(pair) for pair in pairs}
        return (len(pair_set) == len(pairs)
                and pair_set == {(labels[u], labels[v]) for u, v in x["edges"] if labels[u] != labels[v]})
    if type(y["acyclic"]) is not bool or y["acyclic"] != expected["acyclic"]:
        return False
    if task == "topo":
        order = y["order"]
        if not y["acyclic"]:
            return order == []
        if not ints(order) or len(order) != n:
            return False
        positions = [-1] * n
        for position, vertex in enumerate(order):
            if not 0 <= vertex < n or positions[vertex] != -1:
                return False
            positions[vertex] = position
        return all(positions[u] < positions[v] for u, v in x["edges"])
    if task != "dag":
        return False
    if not y["acyclic"]:
        return y["distance"] == [] and y["parent_edge"] == []
    distances, parents = y["distance"], y["parent_edge"]
    if (not isinstance(distances, list) or len(distances) != n
            or any(d is not None and not integer(d) for d in distances)
            or distances != expected["distance"] or not ints(parents) or len(parents) != n):
        return False
    source = x["source"]
    predecessor = [-1] * n
    for v in range(n):
        if v == source or distances[v] is None:
            if parents[v] != -1:
                return False
        else:
            if not 0 <= parents[v] < len(x["edges"]):
                return False
            u, target, weight = x["edges"][parents[v]]
            if target != v or distances[u] is None or distances[u] + weight != distances[v]:
                return False
            predecessor[v] = u
    state = [0] * n
    state[source] = 2
    for v in range(n):
        if distances[v] is None or state[v] == 2:
            continue
        chain, current = [], v
        while state[current] == 0:
            state[current] = 1
            chain.append(current)
            current = predecessor[current]
            if current == -1:
                return False
        if state[current] != 2:
            return False
        for current in chain:
            state[current] = 2
    return True


def witness_self_tests():
    """An oracle/checker must accept valid alternatives and reject bad witnesses."""
    records = []

    def exercise(name, request, accepted, rejected):
        expected = oracle(request)
        for answer in accepted:
            assert validate(request, answer, expected), (name, "valid alternative rejected", answer)
        for answer in rejected:
            assert not validate(request, answer, expected), (name, "invalid witness accepted", answer)
        records.append({"name": name, "request": request, "expected": expected,
                        "valid_alternatives": accepted, "invalid_witnesses": rejected,
                        "accepted_valid": len(accepted), "rejected_invalid": len(rejected), "status": "PASS"})

    for task in ("kruskal", "prim"):
        exercise(task + "-tied-forest", {"task": task, "n": 4, "edges": [[0, 1, 1], [1, 2, 1], [0, 2, 1]]},
                 [{"cost": 2, "edge_ids": [0, 1], "components": 2},
                  {"cost": 2, "edge_ids": [2, 1], "components": 2}],
                 [{"cost": 2, "edge_ids": [0, 0], "components": 2},
                  {"cost": 2, "edge_ids": [0, 1, 2], "components": 2},
                  {"cost": 2, "edge_ids": [0, 9], "components": 2}])
    exercise("scc-renaming", {"task": "scc", "n": 4, "edges": [[0, 1], [1, 0], [1, 2], [0, 2]]},
             [{"component": [10, 10, 30, 99], "condensation": [[10, 30]]},
              {"component": [8, 8, 2, 4], "condensation": [[8, 2]]}],
             [{"component": [10, 10, 30, 99], "condensation": []},
              {"component": [10, 10, 30, 99], "condensation": [[10, 30], [10, 30]]},
              {"component": [10, 10, 10, 99], "condensation": []}])
    exercise("topo-independent-roots", {"task": "topo", "n": 4, "edges": [[0, 2], [1, 2], [2, 3]]},
             [{"acyclic": True, "order": [0, 1, 2, 3]}, {"acyclic": True, "order": [1, 0, 2, 3]}],
             [{"acyclic": True, "order": [2, 0, 1, 3]}, {"acyclic": True, "order": [0, 1, 2, 2]}])
    exercise("dag-tied-parallel-parents", {"task": "dag", "n": 5, "source": 0,
             "edges": [[0, 1, 2], [0, 1, 2], [0, 2, 1], [1, 3, 1], [2, 3, 2]]},
             [{"acyclic": True, "distance": [0, 2, 1, 3, None], "parent_edge": [-1, 0, 2, 3, -1]},
              {"acyclic": True, "distance": [0, 2, 1, 3, None], "parent_edge": [-1, 1, 2, 4, -1]}],
             [{"acyclic": True, "distance": [0, 2, 1, 3, None], "parent_edge": [-1, 2, 2, 3, -1]},
              {"acyclic": True, "distance": [0, 2, 1, 3, None], "parent_edge": [-1, 0, 2, 0, -1]},
              {"acyclic": True, "distance": [0, 2, 1, 3, None], "parent_edge": [-1, 0, 2, 3, 1]}])
    return records


def random_cases():
    rng = random.Random(SEED)
    cases = []
    for i in range(60):
        n = rng.randrange(9)
        operations = [[rng.randrange(n), rng.randrange(n)] for _ in range(rng.randrange(22))] if n else []
        cases.append((f"random-dsu-{i}", {"task": "dsu", "n": n, "operations": operations}))
    for i in range(80):
        n = rng.randrange(8)
        edges = [[rng.randrange(n), rng.randrange(n), rng.randrange(-7, 8)]
                 for _ in range(rng.randrange(12))] if n else []
        for task in ("kruskal", "prim"):
            cases.append((f"random-{task}-{i}", {"task": task, "n": n, "edges": edges}))
    for i in range(60):
        n = rng.randrange(9)
        edges = [[rng.randrange(n), rng.randrange(n)] for _ in range(rng.randrange(18))] if n else []
        for task in ("scc", "topo"):
            cases.append((f"random-{task}-{i}", {"task": task, "n": n, "edges": edges}))
    for i in range(80):
        n = rng.randrange(1, 9)
        source = rng.randrange(n)
        order = list(range(n))
        rng.shuffle(order)
        edges = []
        if i < 60:
            for _ in range(rng.randrange(18)):
                a, b = sorted([rng.randrange(n), rng.randrange(n)])
                if a < b:
                    edges.append([order[a], order[b], rng.randrange(-9, 10)])
        else:
            edges = [[rng.randrange(n), rng.randrange(n), rng.randrange(-9, 10)] for _ in range(rng.randrange(18))]
        cases.append((f"random-dag-{i}", {"task": "dag", "n": n, "source": source, "edges": edges}))
    rng.shuffle(cases)
    return [(name, request, oracle(request)) for name, request in cases]


def scale_cases():
    """Exact constructive oracles, n=100000 and m/q=250000; no reference solver."""
    n, m = 100_000, 250_000
    operations = [[i, i + 1] for i in range(n - 1)]
    operations.extend([[0, n - 1] if i % 2 else [n - 1, n - 1] for i in range(m - len(operations))])
    states = [[n - i - 1, i + 2] for i in range(n - 1)] + [[1, n]] * (m - n + 1)
    yield "limit-dsu", {"task": "dsu", "n": n, "operations": operations}, {"states": states}, "The first n-1 unions join a chain; all remaining unions repeat or self-union."
    del operations, states
    for disconnected in (False, True):
        active = n // 2 if disconnected else n
        weight = -1_000_000_000 if disconnected else 1_000_000_000
        edges = [[i, i + 1, weight] for i in range(active - 1)]
        while len(edges) < m:
            i = len(edges)
            if i % 2:
                edges.append([i % active, i % active, -1_000_000_000])
            else:
                u = i % (active - 1)
                edges.append([u, u + 1, 1_000_000_000])
        expected = {"cost": (active - 1) * weight, "components": n - active + 1}
        for task in ("kruskal", "prim"):
            yield f"limit-{task}-{'disconnected-negative' if disconnected else 'connected-positive'}", {"task": task, "n": n, "edges": edges}, expected, "The only non-loop endpoint pairs form a chain; cheapest available edge for each consecutive pair is mandatory; remaining vertices are isolates."
        del edges
    edges = [[i, i + 1] for i in range(n - 1)] + [[i, i - 1] for i in range(1, n, 2)]
    while len(edges) < m:
        u = len(edges) % n
        edges.append([u, u])
    yield "limit-scc-pairs", {"task": "scc", "n": n, "edges": edges}, {"component": [i // 2 for i in range(n)]}, "Pairs (2k,2k+1) are mutually reachable; all cross-pair edges go forward."
    del edges
    edges = [[i, (i + 1) % n] for i in range(n)]
    while len(edges) < m:
        u = len(edges) % n
        edges.append([u, u])
    yield "limit-scc-single-cycle", {"task": "scc", "n": n, "edges": edges}, {"component": [0] * n}, "The directed spanning cycle reaches every vertex from every vertex."
    del edges
    edges = [[i, i + 1] for i in range(n - 1)]
    while len(edges) < m:
        u = len(edges) % (n - 1)
        edges.append([u, u + 1])
    yield "limit-topo-deep-chain", {"task": "topo", "n": n, "edges": edges}, {"acyclic": True}, "All edges follow the full chain; the unique topological order is 0..n-1."
    del edges
    edges = [[i, i + 1, -1_000_000_000] for i in range(n - 1)]
    while len(edges) < m:
        u = len(edges) % (n - 1)
        edges.append([u, u + 1, 1_000_000_000])
    yield "limit-dag-deep-negative-chain", {"task": "dag", "n": n, "source": 0, "edges": edges}, {"acyclic": True, "distance": [-i * 1_000_000_000 for i in range(n)]}, "Each step must cross one consecutive pair; its cheapest edge is -10^9, so d[i]=-i*10^9."
    del edges
    edges = [[i, i + 1] for i in range(n - 3)] + [[n - 2, n - 1], [n - 1, n - 2]]
    while len(edges) < m:
        u = len(edges) % (n - 3)
        edges.append([u, u + 1])
    yield "limit-topo-disconnected-cycle", {"task": "topo", "n": n, "edges": edges}, {"acyclic": False}, "Vertices n-2 and n-1 form a directed cycle disconnected from the long chain."
    weighted = [[u, v, 1] for u, v in edges]
    del edges
    yield "limit-dag-unreachable-cycle", {"task": "dag", "n": n, "source": 0, "edges": weighted}, {"acyclic": False}, "The last two vertices form a cycle unreachable from source 0; the entire graph must still be rejected."


def json_line(value):
    return json.dumps(value, separators=(",", ":"), ensure_ascii=False) + "\n"


def sha(text):
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def execute(command, requests, timeout=20):
    text = "".join(json_line(request) for request in requests)
    started = time.perf_counter()
    result = subprocess.run(command, input=text, text=True, encoding="utf-8", capture_output=True, timeout=timeout)
    seconds = time.perf_counter() - started
    if result.returncode:
        raise AssertionError(f"Java exit {result.returncode}: {result.stderr[:4000]}")
    lines = result.stdout.splitlines()
    if len(lines) != len(requests):
        raise AssertionError(f"Expected {len(requests)} JSON lines, got {len(lines)}: {result.stdout[:200]}")
    answers = [json.loads(line) for line in lines]
    return answers, seconds, lines


def compact(answer):
    result = {}
    for key, value in answer.items():
        if isinstance(value, list):
            result[key] = {"length": len(value), "first": value[:3], "last": value[-3:]}
        else:
            result[key] = value
    return result


def read_lines(path):
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--quick", action="store_true", help="Skip the full-limit tests.")
    parser.add_argument("--generate-scale", type=pathlib.Path, help="Materialize each full-limit input and its compact oracle; no compilation or test execution.")
    args = parser.parse_args()
    if args.generate_scale:
        args.generate_scale.mkdir(parents=True, exist_ok=True)
        for name, request, expected, reason in scale_cases():
            (args.generate_scale / (name + ".jsonl")).write_text(json_line(request), encoding="utf-8")
            (args.generate_scale / (name + ".expected.json")).write_text(json.dumps({"expected": expected, "reason": reason}), encoding="utf-8")
            print("GENERATED", name)
        return 0
    command = build("Java")
    report = {"command": "python tests/run_tests.py" + (" --quick" if args.quick else ""),
              "python": platform.python_version(), "platform": platform.platform(),
              "java": subprocess.run(["java", "-version"], capture_output=True, text=True, check=True).stderr.strip(),
              "seed": SEED, "jvm_command": command, "per_batch_timeout_seconds": 20,
              "witness_self_tests": witness_self_tests()}
    print("PASS semantic checker self-tests (valid alternatives and invalid witnesses)", flush=True)
    requests = read_lines(HERE / "cases.jsonl")
    expected = read_lines(HERE / "expected.jsonl")
    notes = json.loads((HERE / "case_notes.json").read_text(encoding="utf-8"))
    assert len(requests) == len(expected) == len(notes)
    for request, reference in zip(requests, expected):
        assert validate(request, reference, oracle(request)), "Committed expected answer disagrees with independent small oracle"
    answers, seconds, raw_lines = execute(command, requests)
    for note, request, answer, reference in zip(notes, requests, answers, expected):
        assert validate(request, answer, reference), (note["name"], answer, reference)
    (HERE / "actual.jsonl").write_text("\n".join(raw_lines) + "\n", encoding="utf-8")
    report["handwritten"] = {"count": len(requests), "seconds": seconds, "status": "PASS"}
    print(f"PASS {len(requests)} handwritten cases ({seconds:.3f}s)", flush=True)
    randoms = random_cases()
    mixed = [(note["name"], request, reference) for note, request, reference in zip(notes, requests, expected)] + randoms
    mixed = mixed + [("ping-between-batches", {"task": "ping"}, {"status": "ready"})] + list(reversed(mixed))
    answers, seconds, raw_lines = execute(command, [case[1] for case in mixed])
    for (name, request, reference), answer in zip(mixed, answers):
        assert validate(request, answer, reference), (name, request, answer, reference)
    (HERE / "random_inputs.jsonl").write_text("".join(json_line(case[1]) for case in randoms), encoding="utf-8")
    (HERE / "random_expected.jsonl").write_text("".join(json_line(case[2]) for case in randoms), encoding="utf-8")
    random_answers = raw_lines[len(requests):len(requests) + len(randoms)]
    (HERE / "random_actual.jsonl").write_text("\n".join(random_answers) + "\n", encoding="utf-8")
    report["mixed_random"] = {"random_case_count": len(randoms), "requests_in_one_process": len(mixed),
                              "seconds": seconds, "status": "PASS", "input_sha256": sha("".join(json_line(case[1]) for case in mixed)),
                              "output_sha256": sha("\n".join(raw_lines) + "\n")}
    print(f"PASS {len(mixed)} mixed requests in one JVM; {len(randoms)} seeded differential cases ({seconds:.3f}s)", flush=True)
    output = ROOT / ".build" / "Java"
    subprocess.run(["javac", "--release", "17", "-encoding", "UTF-8", "-cp", str(output), "-d", str(output), str(HERE / "RouteHelperTest.java")], check=True, timeout=60)
    helper = subprocess.run(["java", "-Xmx384m", "-cp", str(output), "RouteHelperTest"], capture_output=True, text=True, encoding="utf-8", check=True, timeout=20)
    helper_data = [json.loads(line) for line in helper.stdout.splitlines()]
    (HERE / "helper_results.json").write_text(json.dumps(helper_data, indent=2) + "\n", encoding="utf-8")
    report["route_helper"] = {"calls": len(helper_data), "status": "PASS"}
    print(f"PASS {len(helper_data)} direct Java route-helper calls", flush=True)
    report["scale"] = []
    if not args.quick:
        resets = [{"task": "ping"}, {"task": "dsu", "n": 2, "operations": [[0, 1], [0, 0]]},
                  {"task": "topo", "n": 0, "edges": []}, {"task": "dag", "n": 1, "source": 0, "edges": []}]
        for name, request, reference, reason in scale_cases():
            answers, seconds, raw_lines = execute(command, [request, *resets])
            assert validate(request, answers[0], reference), (name, compact(answers[0]))
            for reset, answer in zip(resets, answers[1:]):
                assert validate(reset, answer, oracle(reset)), (name, "state reset", reset, answer)
            report["scale"].append({"name": name, "n": request["n"], "m_or_q": len(request.get("edges", request.get("operations", []))),
                                     "expected_reason": reason, "input_sha256": sha(json_line(request)),
                                     "actual_output_sha256": sha(raw_lines[0] + "\n"), "actual": compact(answers[0]),
                                     "seconds_including_four_reset_requests": seconds, "status": "PASS"})
            print(f"PASS {name}: n={request['n']}, m/q=250000; post-case state reset ({seconds:.3f}s)", flush=True)
    report["overall"] = "PASS"
    (HERE / ("results_quick.json" if args.quick else "results.json")).write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print("ALL OWN TESTS PASSED", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
