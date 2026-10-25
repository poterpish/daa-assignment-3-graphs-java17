import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Direct calls: the route helper is deliberately not a new JSON task. */
public final class RouteHelperTest {
    private RouteHelperTest() {
    }

    private static Map<String, Object> dag(int n, int source, int[][] edges) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("n", n);
        request.put("source", source);
        List<List<Integer>> rows = new ArrayList<>();
        for (int[] edge : edges) {
            rows.add(List.of(edge[0], edge[1], edge[2]));
        }
        request.put("edges", rows);
        Map<String, Object> result = Algorithms.dag(request);
        require(Boolean.TRUE.equals(result.get("acyclic")), "Test graph must be acyclic");
        return result;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static Object summary(List<Integer> values) {
        if (values.size() <= 12) {
            return values;
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("length", values.size());
        summary.put("first", values.subList(0, 3));
        summary.put("last", values.subList(values.size() - 3, values.size()));
        return summary;
    }

    private static List<Integer> check(String name, int source, int target, int[][] edges,
            int[] parents, Long expectedCost, List<Integer> exactRoute) {
        List<Integer> route = Algorithms.reconstructRoute(source, target, edges, parents);
        if (exactRoute != null) {
            require(route.equals(exactRoute), name + ": wrong vertex sequence " + route);
        }
        List<Integer> selected = new ArrayList<>();
        long cost = 0L;
        if (expectedCost == null) {
            require(route.isEmpty(), name + ": unreachable target must return []");
        } else {
            require(!route.isEmpty() && route.get(0) == source
                    && route.get(route.size() - 1) == target, name + ": wrong endpoints");
            for (int i = 1; i < route.size(); i++) {
                int u = route.get(i - 1), v = route.get(i);
                int id = parents[v];
                require(id >= 0 && id < edges.length, name + ": bad original edge ID");
                require(edges[id][0] == u && edges[id][1] == v, name + ": wrong edge direction");
                selected.add(id);
                cost += (long) edges[id][2];
            }
            require(cost == expectedCost, name + ": route weight differs from shortest distance");
        }
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("case", name);
        record.put("call", "Algorithms.reconstructRoute(" + source + ", " + target + ", edges, parentEdge)");
        record.put("source", source);
        record.put("target", target);
        if (edges.length <= 12) {
            record.put("original_edges", edges);
            record.put("parent_edge", parents);
        }
        record.put("vertices", summary(route));
        record.put("selected_original_edge_ids", summary(selected));
        record.put("total_weight", expectedCost == null ? null : cost);
        record.put("direction_and_weight_checked", true);
        record.put("status", "PASS");
        System.out.println(Json.write(record));
        return route;
    }

    public static void main(String[] args) {
        int[][] edges = {{0, 1, 3}, {0, 2, 8}, {1, 2, -7}, {2, 3, 4}};
        Map<String, Object> result = dag(5, 0, edges);
        Long[] distance = (Long[]) result.get("distance");
        int[] parentEdge = (int[]) result.get("parent_edge");
        require(Arrays.equals(distance, new Long[] {0L, 3L, -4L, 0L, null}), "Wrong demo distances");
        check("reachable", 0, 3, edges, parentEdge, distance[3], List.of(0, 1, 2, 3));
        check("source", 0, 0, edges, parentEdge, 0L, List.of(0));
        check("unreachable", 0, 4, edges, parentEdge, null, List.of());

        int[][] tied = {{0, 1, 2}, {0, 1, 2}, {0, 2, 1}, {1, 3, 1}, {2, 3, 2}};
        result = dag(5, 0, tied);
        distance = (Long[]) result.get("distance");
        parentEdge = (int[]) result.get("parent_edge");
        require(Objects.equals(distance[3], 3L), "Wrong tied shortest distance");
        check("tied-result-from-dag", 0, 3, tied, parentEdge, distance[3], null);
        // Both manually supplied witnesses are valid: edge IDs 0 and 1 are distinct parallel edges.
        int[] parallelAlternative = {-1, 1, 2, 3, -1};
        check("parallel-edge-id-1", 0, 3, tied, parallelAlternative, 3L, List.of(0, 1, 3));
        int[] tiedAlternative = {-1, 0, 2, 4, -1};
        check("alternative-tied-path", 0, 3, tied, tiedAlternative, 3L, List.of(0, 2, 3));

        int n = 100_000;
        int[][] deep = new int[n - 1][3];
        int[] parents = new int[n];
        parents[0] = -1;
        for (int v = 1; v < n; v++) {
            deep[v - 1] = new int[] {v - 1, v, 1_000_000_000};
            parents[v] = v - 1;
        }
        List<Integer> route = check("deep-100000-vertex-route", 0, n - 1, deep, parents,
                (n - 1L) * 1_000_000_000L, null);
        require(route.size() == n, "Deep helper route lost vertices");
        for (int v = 0; v < n; v++) {
            require(route.get(v) == v, "Deep helper route order incorrect");
        }
    }
}
