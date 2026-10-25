import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Reproducible in-process benchmark. Validation and input generation are untimed. */
public final class Benchmark {
    private static final int WARM_UP = 8;
    private static final int REPETITIONS = 7;
    private static volatile long checksum;

    private record Graph(int n, int[][] edges, Map<String, Object> request,
                         long seed, String sha256) { }

    /** Deliberately separate from Algorithms' DSU: benchmark witness validation only. */
    private static final class Partition {
        final int[] parent;
        final int[] rank;
        int count;

        Partition(int n) {
            parent = new int[n];
            rank = new int[n];
            count = n;
            for (int v = 0; v < n; v++) parent[v] = v;
        }

        int root(int v) {
            while (parent[v] != v) {
                parent[v] = parent[parent[v]];
                v = parent[v];
            }
            return v;
        }

        boolean join(int u, int v) {
            u = root(u);
            v = root(v);
            if (u == v) return false;
            if (rank[u] < rank[v]) {
                int t = u;
                u = v;
                v = t;
            }
            parent[v] = u;
            if (rank[u] == rank[v]) rank[u]++;
            count--;
            return true;
        }
    }

    private static Graph generate(int n, int m) throws Exception {
        long seed = 20261025L + n;
        Random random = new Random(seed);
        int[][] edges = new int[m][3];
        for (int i = 0; i < m; i++) {
            edges[i][0] = i < n - 1 ? i : random.nextInt(n);
            edges[i][1] = i < n - 1 ? i + 1 : random.nextInt(n);
            edges[i][2] = random.nextInt(2_000_000_001) - 1_000_000_000;
        }
        // The chain guarantees connectivity, but its weights are ordinary random weights.
        for (int i = m - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            int[] t = edges[i];
            edges[i] = edges[j];
            edges[j] = t;
        }
        List<List<Integer>> rows = new ArrayList<>(m);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        ByteBuffer buffer = ByteBuffer.allocate(12); // Big-endian signed 32-bit integers.
        buffer.putInt(n).putInt(m);
        digest.update(buffer.array(), 0, 8);
        for (int[] edge : edges) {
            rows.add(List.of(edge[0], edge[1], edge[2]));
            buffer.clear();
            buffer.putInt(edge[0]).putInt(edge[1]).putInt(edge[2]);
            digest.update(buffer.array());
        }
        return new Graph(n, edges, Map.of("n", n, "edges", rows), seed,
                         HexFormat.of().formatHex(digest.digest()));
    }

    private static Map<String, Object> call(Graph graph, boolean kruskal) {
        return kruskal ? Algorithms.kruskal(graph.request) : Algorithms.prim(graph.request);
    }

    private static List<Integer> ids(Object value) {
        List<Integer> result = new ArrayList<>();
        if (value instanceof int[] array) {
            for (int id : array) result.add(id);
        } else if (value instanceof Iterable<?> iterable) {
            for (Object id : iterable) result.add(((Number) id).intValue());
        } else {
            throw new AssertionError("Unrecognized edge_ids representation");
        }
        return result;
    }

    /**
     * Independent optimality certificate: a spanning forest T is minimum iff every
     * original nonloop edge (u,v,w) has w >= max weight on the T path from u to v.
     * Binary lifting checks all paths in O((n+m) log n), outside measured calls.
     */
    private static long verify(Graph graph, Map<String, Object> result) {
        int n = graph.n;
        int[][] edges = graph.edges;
        int components = ((Number) result.get("components")).intValue();
        long reportedCost = ((Number) result.get("cost")).longValue();
        List<Integer> selected = ids(result.get("edge_ids"));
        Partition input = new Partition(n);
        Partition forest = new Partition(n);
        for (int[] edge : edges) input.join(edge[0], edge[1]);
        require(components == input.count, "wrong component count");
        require(selected.size() == n - components, "wrong forest size");

        int[] head = new int[n];
        Arrays.fill(head, -1);
        int[] to = new int[2 * selected.size()];
        int[] next = new int[to.length];
        long[] weight = new long[to.length];
        boolean[] used = new boolean[edges.length];
        long total = 0;
        int arc = 0;
        for (int id : selected) {
            require(id >= 0 && id < edges.length && !used[id], "bad/repeated edge ID");
            used[id] = true;
            int[] edge = edges[id];
            int u = edge[0], v = edge[1];
            require(forest.join(u, v), "selected loop or cycle");
            total += edge[2];
            to[arc] = v; weight[arc] = edge[2]; next[arc] = head[u]; head[u] = arc++;
            to[arc] = u; weight[arc] = edge[2]; next[arc] = head[v]; head[v] = arc++;
        }
        require(total == reportedCost, "reported cost differs from witness");
        for (int[] edge : edges) {
            require(forest.root(edge[0]) == forest.root(edge[1]), "component is not spanned");
        }

        int levels = 1;
        while ((1L << levels) <= Math.max(1, n)) levels++;
        int[][] up = new int[levels][n];
        long[][] max = new long[levels][n];
        for (long[] row : max) Arrays.fill(row, Long.MIN_VALUE);
        int[] depth = new int[n];
        Arrays.fill(depth, -1);
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int root = 0; root < n; root++) {
            if (depth[root] != -1) continue;
            depth[root] = 0;
            up[0][root] = root;
            queue.add(root);
            while (!queue.isEmpty()) {
                int u = queue.remove();
                for (int e = head[u]; e != -1; e = next[e]) {
                    int v = to[e];
                    if (depth[v] != -1) continue;
                    depth[v] = depth[u] + 1;
                    up[0][v] = u;
                    max[0][v] = weight[e];
                    queue.add(v);
                }
            }
        }
        for (int k = 1; k < levels; k++) {
            for (int v = 0; v < n; v++) {
                int middle = up[k - 1][v];
                up[k][v] = up[k - 1][middle];
                max[k][v] = Math.max(max[k - 1][v], max[k - 1][middle]);
            }
        }
        for (int[] edge : edges) {
            if (edge[0] == edge[1]) continue;
            long pathMaximum = maximumOnPath(edge[0], edge[1], depth, up, max);
            require(pathMaximum <= edge[2], "cycle-property optimality certificate failed");
        }
        return total;
    }

    private static long maximumOnPath(int u, int v, int[] depth,
                                      int[][] up, long[][] max) {
        long answer = Long.MIN_VALUE;
        if (depth[u] < depth[v]) {
            int t = u; u = v; v = t;
        }
        int difference = depth[u] - depth[v];
        for (int k = 0; k < up.length; k++) {
            if ((difference & (1 << k)) != 0) {
                answer = Math.max(answer, max[k][u]);
                u = up[k][u];
            }
        }
        if (u == v) return answer;
        for (int k = up.length - 1; k >= 0; k--) {
            if (up[k][u] != up[k][v]) {
                answer = Math.max(answer, Math.max(max[k][u], max[k][v]));
                u = up[k][u]; v = up[k][v];
            }
        }
        return Math.max(answer, Math.max(max[0][u], max[0][v]));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static long timed(Graph graph, boolean kruskal, long expectedCost) {
        long begin = System.nanoTime();
        Map<String, Object> result = call(graph, kruskal);
        long elapsed = System.nanoTime() - begin;
        // Outside the timer: consume the result and detect unexpected changes.
        long cost = ((Number) result.get("cost")).longValue();
        require(cost == expectedCost, "timed run changed optimum cost");
        checksum ^= cost;
        return elapsed;
    }

    private static Map<String, Object> measurements(long[] nanoseconds) {
        long[] sorted = nanoseconds.clone();
        Arrays.sort(sorted);
        List<Double> milliseconds = new ArrayList<>();
        for (long time : nanoseconds) milliseconds.add(time / 1_000_000.0);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("samples_ns", nanoseconds);
        result.put("samples_ms", milliseconds);
        result.put("median_ms", sorted[sorted.length / 2] / 1_000_000.0);
        return result;
    }

    public static void main(String[] args) throws Exception {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("java_runtime", System.getProperty("java.runtime.version"));
        report.put("java_vm", System.getProperty("java.vm.name"));
        report.put("java_vendor", System.getProperty("java.vendor"));
        report.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
        report.put("architecture", System.getProperty("os.arch"));
        report.put("available_processors", Runtime.getRuntime().availableProcessors());
        report.put("max_heap_bytes", Runtime.getRuntime().maxMemory());
        report.put("jvm_arguments", ManagementFactory.getRuntimeMXBean().getInputArguments());
        report.put("timer", "System.nanoTime() elapsed nanoseconds; displayed in milliseconds");
        report.put("warm_up_calls_per_method_per_size", WARM_UP);
        report.put("measured_calls_per_method_per_size", REPETITIONS);
        report.put("order", "Alternate Kruskal-first and Prim-first pairs, warm-up and measurement");
        report.put("timing_boundary", "One complete Algorithms.kruskal/prim call: includes Input.edges "
                + "conversion, algorithm structures, sorting/heap work and result creation; excludes "
                + "compilation, JVM startup, generator, boxed request creation, JSON, validation and output");
        report.put("generator", "java.util.Random seed=20261025+n; random weighted chain, then random "
                + "endpoint pairs; all weights uniform integer [-1000000000,1000000000]; Fisher-Yates shuffle");
        report.put("graph_hash_format", "SHA-256 of n,m then edge rows u,v,w, each signed int32 big-endian");

        List<Map<String, Object>> rows = new ArrayList<>();
        for (int n : new int[] {1_000, 10_000, 50_000}) {
            Graph graph = generate(n, 4 * n);
            long optimum = verify(graph, call(graph, true));
            require(verify(graph, call(graph, false)) == optimum, "methods disagree");
            for (int i = 0; i < WARM_UP; i++) {
                timed(graph, i % 2 == 0, optimum);
                timed(graph, i % 2 != 0, optimum);
            }
            long[] kruskalTimes = new long[REPETITIONS];
            long[] primTimes = new long[REPETITIONS];
            for (int i = 0; i < REPETITIONS; i++) {
                if (i % 2 == 0) {
                    kruskalTimes[i] = timed(graph, true, optimum);
                    primTimes[i] = timed(graph, false, optimum);
                } else {
                    primTimes[i] = timed(graph, false, optimum);
                    kruskalTimes[i] = timed(graph, true, optimum);
                }
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("n", n);
            row.put("m", graph.edges.length);
            row.put("seed", graph.seed);
            row.put("graph_sha256", graph.sha256);
            row.put("components", 1);
            row.put("optimum_cost", optimum);
            row.put("validation", "PASS: IDs, acyclicity, spanning, sum, components, cycle-property certificate");
            row.put("kruskal", measurements(kruskalTimes));
            row.put("prim", measurements(primTimes));
            rows.add(row);
            System.err.println("Validated and measured n=" + n + ", m=" + graph.edges.length);
        }
        report.put("sizes", rows);
        report.put("checksum", checksum);
        System.out.println(Json.write(report));
    }
}
