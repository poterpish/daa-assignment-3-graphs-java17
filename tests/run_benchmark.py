"""Compile, run and save actual in-JVM Kruskal/Prim benchmark measurements."""
from __future__ import annotations

import argparse
import datetime
import json
import os
from pathlib import Path
import platform
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]


def host_information() -> dict:
    result = {"platform": platform.platform(), "python": platform.python_version(),
              "logical_cpus_visible": os.cpu_count()}
    try:
        result["cpu"] = next(line.split(":", 1)[1].strip()
                             for line in Path("/proc/cpuinfo").read_text().splitlines()
                             if line.startswith("model name"))
    except (OSError, StopIteration):
        result["cpu"] = platform.processor() or "not exposed by this environment"
    if hasattr(os, "sched_getaffinity"):
        result["affinity_cpus"] = len(os.sched_getaffinity(0))
    for name, path in (("cpu_quota", "/sys/fs/cgroup/cpu.max"),
                       ("memory_limit", "/sys/fs/cgroup/memory.max")):
        try:
            result[name] = Path(path).read_text().strip()
        except OSError:
            result[name] = "not exposed by this environment"
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=ROOT / "tests/benchmark_results.json")
    parser.add_argument("--host-info", type=Path,
                        help="Optional actual host-info JSON collected in the same environment")
    args = parser.parse_args()
    output = ROOT / ".build/Benchmark"
    output.mkdir(parents=True, exist_ok=True)
    compile_command = ["javac", "--release", "17", "-encoding", "UTF-8", "-d", str(output),
                       *map(str, sorted((ROOT / "src/Java").glob("*.java"))),
                       str(ROOT / "tests/Benchmark.java")]
    subprocess.run(compile_command, check=True, timeout=60)
    run_command = ["java", "-Xms128m", "-Xmx384m", "-cp", str(output), "Benchmark"]
    completed = subprocess.run(run_command, check=True, capture_output=True,
                               encoding="utf-8", timeout=180)
    if completed.stderr:
        print(completed.stderr, end="", file=sys.stderr)
    report = json.loads(completed.stdout)
    report["measured_at_utc"] = datetime.datetime.now(datetime.timezone.utc).isoformat()
    report["host"] = (json.loads(args.host_info.read_text(encoding="utf-8"))
                      if args.host_info else host_information())
    report["commands"] = {
        "reproduce": "python tests/run_benchmark.py",
        "compile": "javac --release 17 -encoding UTF-8 -d .build/Benchmark src/Java/*.java tests/Benchmark.java",
        "run": "java -Xms128m -Xmx384m -cp .build/Benchmark Benchmark",
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print("n,m,kruskal_median_ms,prim_median_ms")
    for row in report["sizes"]:
        print(f'{row["n"]},{row["m"]},{row["kruskal"]["median_ms"]:.6f},'
              f'{row["prim"]["median_ms"]:.6f}')
    print(f"Saved actual samples to {args.output.relative_to(ROOT) if args.output.is_relative_to(ROOT) else args.output}")


if __name__ == "__main__":
    main()
