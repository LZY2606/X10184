package com.regmold.validate;

import com.regmold.domain.Effect;
import com.regmold.domain.EffectAction;
import com.regmold.domain.Ref;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Analyses the directed side-effect graph.
 *
 * <p>A contradictory chain is a node reachable from the same trigger through both a
 * SET-only and a CLEAR-only path (the hardware cannot pick a deterministic outcome).
 * Cycles are reported separately. All results are shortest paths, with lexicographic
 * tie-breaking so output is deterministic.
 */
public final class ConflictFinder {

    public record Result(List<ConflictPath> conflicts, List<Path> cycles) {
    }

    /** A directed path of effects. */
    public static class Path {
        protected final List<Ref> nodes;
        protected final List<Effect> edges;

        Path(List<Ref> nodes, List<Effect> edges) {
            this.nodes = List.copyOf(nodes);
            this.edges = List.copyOf(edges);
        }

        public List<Ref> nodes() {
            return nodes;
        }

        public List<Effect> edges() {
            return edges;
        }

        public int length() {
            return edges.size();
        }

        public String render() {
            StringBuilder sb = new StringBuilder(nodes.get(0).canonical());
            for (int i = 0; i < edges.size(); i++) {
                sb.append(" --").append(edges.get(i).action().name().toLowerCase())
                        .append("--> ").append(nodes.get(i + 1).canonical());
            }
            return sb.toString();
        }
    }

    /** Two paths from one trigger to the same target, one SET-only and one CLEAR-only. */
    public static final class ConflictPath {
        private final Path setPath;
        private final Path clearPath;

        ConflictPath(Path setPath, Path clearPath) {
            this.setPath = setPath;
            this.clearPath = clearPath;
        }

        public Path setPath() {
            return setPath;
        }

        public Path clearPath() {
            return clearPath;
        }

        public int totalLength() {
            return setPath.length() + clearPath.length();
        }

        public String trigger() {
            return setPath.nodes().get(0).canonical();
        }

        public String target() {
            return setPath.nodes().get(setPath.nodes().size() - 1).canonical();
        }

        public String render() {
            return "trigger " + trigger() + " can both SET and CLEAR " + target()
                    + " | SET: " + setPath.render().substring(setPath.render().indexOf(' ') + 1)
                    + " | CLEAR: " + clearPath.render().substring(clearPath.render().indexOf(' ') + 1);
        }
    }

    private record Edge(Ref from, EffectAction action, Effect effect) {
    }

    private final Map<String, Ref> nodes = new LinkedHashMap<>();
    private final Map<String, List<Edge>> outgoing = new HashMap<>();

    public ConflictFinder(List<Effect> effects) {
        for (Effect e : effects) {
            put(e.trigger());
            put(e.target());
            outgoing.computeIfAbsent(e.trigger().canonical(), k -> new ArrayList<>())
                    .add(new Edge(e.trigger(), e.action(), e));
        }
        for (List<Edge> es : outgoing.values()) {
            es.sort((a, b) -> a.effect().target().canonical().compareTo(b.effect().target().canonical()));
        }
    }

    private void put(Ref r) {
        nodes.putIfAbsent(r.canonical(), r);
    }

    public Result analyze() {
        return new Result(findConflicts(), findCycles());
    }

    private List<ConflictPath> findConflicts() {
        List<ConflictPath> candidates = new ArrayList<>();
        for (Ref source : nodes.values()) {
            Bfs setBfs = bfs(source, EffectAction.SET);
            Bfs clearBfs = bfs(source, EffectAction.CLEAR);
            Set<String> shared = new HashSet<>(setBfs.dist.keySet());
            shared.retainAll(clearBfs.dist.keySet());
            shared.remove(source.canonical());
            for (String targetKey : shared) {
                Path setPath = setBfs.reconstruct(nodes.get(targetKey));
                Path clearPath = clearBfs.reconstruct(nodes.get(targetKey));
                candidates.add(new ConflictPath(setPath, clearPath));
            }
        }
        candidates.sort((a, b) -> {
            if (a.totalLength() != b.totalLength()) {
                return Integer.compare(a.totalLength(), b.totalLength());
            }
            if (!a.trigger().equals(b.trigger())) {
                return a.trigger().compareTo(b.trigger());
            }
            return a.target().compareTo(b.target());
        });
        return candidates;
    }

    private List<Path> findCycles() {
        // For each edge (u -> start) closing a shortest start-rooted tree, reconstruct.
        List<Path> found = new ArrayList<>();
        for (Ref start : nodes.values()) {
            Bfs bfs = bfsAll(start);
            int bestLen = Integer.MAX_VALUE;
            Path best = null;
            for (Map.Entry<String, List<Edge>> entry : outgoing.entrySet()) {
                String u = entry.getKey();
                if (!bfs.dist.containsKey(u)) {
                    continue;
                }
                for (Edge edge : entry.getValue()) {
                    if (!edge.effect().target().canonical().equals(start.canonical())) {
                        continue;
                    }
                    Path prefix = bfs.reconstruct(nodes.get(u));
                    List<Ref> ns = new ArrayList<>(prefix.nodes());
                    ns.add(start);
                    List<Effect> es = new ArrayList<>(prefix.edges());
                    es.add(edge.effect());
                    Path p = new Path(ns, es);
                    if (p.length() < bestLen
                            || (p.length() == bestLen && best != null && cycleKey(p).compareTo(cycleKey(best)) < 0)) {
                        bestLen = p.length();
                        best = p;
                    }
                }
            }
            if (best != null) {
                found.add(best);
            }
        }
        found.sort((a, b) -> {
            String ka = cycleKey(a);
            String kb = cycleKey(b);
            if (a.length() != b.length()) {
                return Integer.compare(a.length(), b.length());
            }
            return ka.compareTo(kb);
        });
        List<Path> unique = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Path p : found) {
            if (seen.add(cycleKey(p))) {
                unique.add(p);
            }
        }
        return unique;
    }

    /** Canonical rotation-invariant key for a directed cycle. */
    static String cycleKey(Path p) {
        List<String> names = new ArrayList<>();
        int count = p.nodes().size() - 1;
        for (int i = 0; i < count; i++) {
            names.add(p.nodes().get(i).canonical());
        }
        int min = 0;
        for (int i = 1; i < count; i++) {
            if (names.get(i).compareTo(names.get(min)) < 0) {
                min = i;
            }
        }
        List<String> rotated = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            rotated.add(names.get((min + i) % count));
        }
        return String.join("->", rotated);
    }

    private Bfs bfs(Ref source, EffectAction action) {
        Map<String, Integer> dist = new LinkedHashMap<>();
        Map<String, Edge> pred = new HashMap<>();
        Deque<String> queue = new ArrayDeque<>();
        dist.put(source.canonical(), 0);
        queue.add(source.canonical());
        while (!queue.isEmpty()) {
            String cur = queue.poll();
            for (Edge edge : outgoing.getOrDefault(cur, List.of())) {
                if (edge.action() != action) {
                    continue;
                }
                String next = edge.effect().target().canonical();
                if (!dist.containsKey(next)) {
                    dist.put(next, dist.get(cur) + 1);
                    pred.put(next, edge);
                    queue.add(next);
                }
            }
        }
        return new Bfs(dist, pred, nodes);
    }

    private Bfs bfsAll(Ref source) {
        Map<String, Integer> dist = new LinkedHashMap<>();
        Map<String, Edge> pred = new HashMap<>();
        Deque<String> queue = new ArrayDeque<>();
        dist.put(source.canonical(), 0);
        queue.add(source.canonical());
        while (!queue.isEmpty()) {
            String cur = queue.poll();
            for (Edge edge : outgoing.getOrDefault(cur, List.of())) {
                String next = edge.effect().target().canonical();
                if (!dist.containsKey(next)) {
                    dist.put(next, dist.get(cur) + 1);
                    pred.put(next, edge);
                    queue.add(next);
                }
            }
        }
        return new Bfs(dist, pred, nodes);
    }

    private static final class Bfs {
        final Map<String, Integer> dist;
        final Map<String, Edge> pred;
        final Map<String, Ref> allNodes;

        Bfs(Map<String, Integer> dist, Map<String, Edge> pred, Map<String, Ref> allNodes) {
            this.dist = dist;
            this.pred = pred;
            this.allNodes = allNodes;
        }

        Path reconstruct(Ref target) {
            List<Effect> rev = new ArrayList<>();
            String cur = target.canonical();
            while (pred.containsKey(cur)) {
                Edge edge = pred.get(cur);
                rev.add(edge.effect());
                cur = edge.from().canonical();
            }
            List<Effect> es = new ArrayList<>();
            for (int i = rev.size() - 1; i >= 0; i--) {
                es.add(rev.get(i));
            }
            List<Ref> ns = new ArrayList<>();
            ns.add(allNodes.get(cur));
            for (Effect e : es) {
                ns.add(e.target());
            }
            return new Path(ns, es);
        }
    }
}
