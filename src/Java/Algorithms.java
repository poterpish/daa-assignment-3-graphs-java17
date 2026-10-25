import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Assignment 3 graph algorithms. Every request owns all of its mutable state.
 * Traversals use explicit stacks, so a 100,000-vertex chain needs no recursion.
 */
public final class Algorithms {
    private Algorithms() {
    }

    /** Path-compressed DSU with union by size and one state after each union. */
    static Map<String, Object> dsu(Map<String, Object> input) {
        int n = Input.vertexCount(input);
        int[][] operations = Input.operations(input);
        DisjointSet sets = new DisjointSet(n);
        int[][] states = new int[operations.length][2];
        for (int i = 0; i < operations.length; i++) {
            sets.union(operations[i][0], operations[i][1]);
            states[i][0] = sets.components;
            states[i][1] = sets.largest;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("states", states);
        return result;
    }

    /** Kruskal: original edge IDs are sorted; the input rows are never reordered. */
    static Map<String, Object> kruskal(Map<String, Object> input) {
        int n = Input.vertexCount(input);
        int[][] edges = Input.edges(input);
        Integer[] order = new Integer[edges.length];
        for (int id = 0; id < edges.length; id++) {
            order[id] = id;
        }
        Arrays.sort(order, edgeComparator(edges));
        DisjointSet sets = new DisjointSet(n);
        List<Integer> selected = new ArrayList<>();
        long cost = 0L;
        for (int id : order) {
            int[] edge = edges[id];
            if (sets.union(edge[0], edge[1])) {
                selected.add(id);
                cost += (long) edge[2];
            }
        }
        return forestResult(cost, selected, sets.components);
    }

    /** Prim: a lazy frontier heap, restarted for every unvisited component. */
    static Map<String, Object> prim(Map<String, Object> input) {
        int n = Input.vertexCount(input);
        int[][] edges = Input.edges(input);
        int[][] incident = adjacency(n, edges, false, true);
        boolean[] visited = new boolean[n];
        PriorityQueue<Integer> frontier = new PriorityQueue<>(edgeComparator(edges));
        List<Integer> selected = new ArrayList<>();
        long cost = 0L;
        int components = 0;
        for (int root = 0; root < n; root++) {
            if (visited[root]) {
                continue;
            }
            components++;
            visitForPrim(root, edges, incident, visited, frontier);
            while (!frontier.isEmpty()) {
                int id = frontier.remove();
                int[] edge = edges[id];
                if (visited[edge[0]] && visited[edge[1]]) {
                    continue; // A lazy heap entry may no longer cross the cut.
                }
                int next = visited[edge[0]] ? edge[1] : edge[0];
                selected.add(id);
                cost += (long) edge[2];
                visitForPrim(next, edges, incident, visited, frontier);
            }
        }
        return forestResult(cost, selected, components);
    }

    /** Kosaraju: reverse finish order on the graph, then DFS on its transpose. */
    static Map<String, Object> scc(Map<String, Object> input) {
        int n = Input.vertexCount(input);
        int[][] edges = Input.edges(input);
        int[][] outgoing = adjacency(n, edges, false, false);
        int[][] incoming = adjacency(n, edges, true, false);
        int[] order = reverseFinishOrder(n, edges, outgoing, false);
        int[] component = new int[n];
        Arrays.fill(component, -1);
        int[] stack = new int[n];
        int[] nextEdge = new int[n];
        int count = 0;
        for (int root : order) {
            if (component[root] != -1) {
                continue;
            }
            int top = 0;
            stack[top] = root;
            component[root] = count;
            while (top >= 0) {
                int vertex = stack[top];
                if (nextEdge[vertex] == incoming[vertex].length) {
                    top--;
                    continue;
                }
                int id = incoming[vertex][nextEdge[vertex]++];
                int predecessor = edges[id][0];
                if (component[predecessor] == -1) {
                    component[predecessor] = count;
                    stack[++top] = predecessor;
                }
            }
            count++;
        }
        Set<Long> distinct = new HashSet<>();
        List<int[]> condensation = new ArrayList<>();
        for (int[] edge : edges) {
            int from = component[edge[0]];
            int to = component[edge[1]];
            if (from != to) {
                long pair = ((long) from << 32) | (to & 0xffffffffL);
                // Mix the packed pair to avoid Long's poor hash on long chains.
                // The mixing is bijective, so it cannot merge distinct pairs.
                if (distinct.add(mixPair(pair))) {
                    condensation.add(new int[] {from, to});
                }
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("component", component);
        result.put("condensation", condensation);
        return result;
    }

    /** Three-state DFS: an edge to an active vertex is a directed cycle. */
    static Map<String, Object> topo(Map<String, Object> input) {
        int n = Input.vertexCount(input);
        int[][] edges = Input.edges(input);
        int[][] outgoing = adjacency(n, edges, false, false);
        int[] order = reverseFinishOrder(n, edges, outgoing, true);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("acyclic", order != null);
        result.put("order", order == null ? new int[0] : order);
        return result;
    }

    /** Whole-graph cycle check followed by topological shortest-path relaxation. */
    static Map<String, Object> dag(Map<String, Object> input) {
        int n = Input.vertexCount(input);
        int source = Input.source(input);
        int[][] edges = Input.edges(input);
        int[][] outgoing = adjacency(n, edges, false, false);
        int[] order = reverseFinishOrder(n, edges, outgoing, true);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("acyclic", order != null);
        if (order == null) {
            result.put("distance", new Long[0]);
            result.put("parent_edge", new int[0]);
            return result;
        }
        Long[] distance = new Long[n]; // null is unreachable; -1 is a valid cost.
        int[] parentEdge = new int[n];
        Arrays.fill(parentEdge, -1);
        distance[source] = 0L;
        for (int vertex : order) {
            if (distance[vertex] == null) {
                continue;
            }
            for (int id : outgoing[vertex]) {
                int target = edges[id][1];
                long candidate = distance[vertex] + (long) edges[id][2];
                if (distance[target] == null || candidate < distance[target]) {
                    distance[target] = candidate;
                    parentEdge[target] = id;
                }
            }
        }
        result.put("distance", distance);
        result.put("parent_edge", parentEdge);
        return result;
    }

    /**
     * Reconstruct the selected DAG route from original incoming edge IDs.
     * The source route is [source]; an unreachable target returns an empty list.
     * Call only with a successful acyclic DAG result and its original edges.
     * Valid routes take O(L) time and output space for L vertices.
     */
    static List<Integer> reconstructRoute(
            int source, int target, int[][] edges, int[] parentEdge) {
        int n = parentEdge.length;
        if (source < 0 || source >= n || target < 0 || target >= n) {
            throw new IllegalArgumentException("Source or target outside parent array");
        }
        if (target == source) {
            return List.of(source);
        }
        if (parentEdge[target] == -1) {
            return List.of();
        }
        List<Integer> reversed = new ArrayList<>();
        int vertex = target;
        while (vertex != source) {
            if (reversed.size() >= n) {
                throw new IllegalArgumentException("Cycle in parent-edge chain");
            }
            reversed.add(vertex);
            int id = parentEdge[vertex];
            if (id < 0 || id >= edges.length || edges[id][1] != vertex) {
                throw new IllegalArgumentException("Invalid incoming parent edge");
            }
            vertex = edges[id][0];
            if (vertex < 0 || vertex >= n) {
                throw new IllegalArgumentException("Parent vertex outside graph");
            }
        }
        reversed.add(source);
        Collections.reverse(reversed);
        return reversed;
    }

    /** Build exact-size adjacency arrays holding original edge IDs. */
    private static int[][] adjacency(
            int n, int[][] edges, boolean transpose, boolean undirected) {
        int[] degree = new int[n];
        for (int[] edge : edges) {
            degree[transpose ? edge[1] : edge[0]]++;
            if (undirected) {
                degree[edge[1]]++;
            }
        }
        int[][] adjacent = new int[n][];
        for (int vertex = 0; vertex < n; vertex++) {
            adjacent[vertex] = new int[degree[vertex]];
        }
        Arrays.fill(degree, 0);
        for (int id = 0; id < edges.length; id++) {
            int vertex = transpose ? edges[id][1] : edges[id][0];
            adjacent[vertex][degree[vertex]++] = id;
            if (undirected) {
                vertex = edges[id][1];
                adjacent[vertex][degree[vertex]++] = id;
            }
        }
        return adjacent;
    }

    /**
     * Simulate recursive DFS frames with a vertex stack and a saved edge cursor.
     * Return reverse finish order, or null if rejectCycles and a back edge exists.
     */
    private static int[] reverseFinishOrder(
            int n, int[][] edges, int[][] outgoing, boolean rejectCycles) {
        byte[] state = new byte[n]; // 0 = unvisited, 1 = active, 2 = finished.
        int[] stack = new int[n];
        int[] nextEdge = new int[n];
        int[] order = new int[n];
        int position = n;
        for (int root = 0; root < n; root++) {
            if (state[root] != 0) {
                continue;
            }
            int top = 0;
            stack[top] = root;
            state[root] = 1;
            while (top >= 0) {
                int vertex = stack[top];
                if (nextEdge[vertex] == outgoing[vertex].length) {
                    state[vertex] = 2;
                    order[--position] = vertex;
                    top--;
                    continue;
                }
                int id = outgoing[vertex][nextEdge[vertex]++];
                int target = edges[id][1];
                if (state[target] == 0) {
                    state[target] = 1;
                    stack[++top] = target;
                } else if (rejectCycles && state[target] == 1) {
                    return null;
                }
            }
        }
        return order;
    }

    private static Comparator<Integer> edgeComparator(int[][] edges) {
        return Comparator.<Integer>comparingInt(id -> edges[id][2])
                .thenComparingInt(id -> id);
    }

    private static void visitForPrim(int vertex, int[][] edges, int[][] incident,
            boolean[] visited, PriorityQueue<Integer> frontier) {
        visited[vertex] = true;
        for (int id : incident[vertex]) {
            int[] edge = edges[id];
            int other = edge[0] == vertex ? edge[1] : edge[0];
            if (!visited[other]) {
                frontier.add(id);
            }
        }
    }

    private static Map<String, Object> forestResult(
            long cost, List<Integer> selected, int components) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("cost", cost);
        result.put("edge_ids", selected);
        result.put("components", components);
        return result;
    }

    /**
     * Stafford Mix13 / SplitMix64 finalizer; wrapping arithmetic is intentional.
     * Source: https://zimbry.blogspot.com/2011/09/better-bit-mixing-improving-on.html
     * This is only a hash-key transformation, not a supplied graph algorithm.
     */
    private static long mixPair(long pair) {
        pair = (pair ^ (pair >>> 30)) * 0xbf58476d1ce4e5b9L;
        pair = (pair ^ (pair >>> 27)) * 0x94d049bb133111ebL;
        return pair ^ (pair >>> 31);
    }

    private static final class DisjointSet {
        private final int[] parent;
        private final int[] size;
        private int components;
        private int largest;

        private DisjointSet(int n) {
            parent = new int[n];
            size = new int[n];
            components = n;
            largest = n == 0 ? 0 : 1;
            for (int vertex = 0; vertex < n; vertex++) {
                parent[vertex] = vertex;
                size[vertex] = 1;
            }
        }

        private int find(int vertex) {
            int root = vertex;
            while (parent[root] != root) {
                root = parent[root];
            }
            while (vertex != root) {
                int previousParent = parent[vertex];
                parent[vertex] = root;
                vertex = previousParent;
            }
            return root;
        }

        private boolean union(int first, int second) {
            int a = find(first);
            int b = find(second);
            if (a == b) {
                return false;
            }
            if (size[a] < size[b]) {
                int temporary = a;
                a = b;
                b = temporary;
            }
            parent[b] = a;
            size[a] += size[b];
            components--;
            largest = Math.max(largest, size[a]);
            return true;
        }
    }
}
