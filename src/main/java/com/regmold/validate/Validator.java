package com.regmold.validate;

import com.regmold.model.*;

import java.util.*;

/**
 * Structural and behavioral validation:
 *  - field overlap / out-of-range
 *  - alias address conflicts (aliases may share addresses, strangers may not)
 *  - lock references
 *  - side-effect cycles and contradictory chains, reported with the shortest conflict path
 */
public class Validator {

    public List<Conflict> validate(ChipModel m) {
        List<Conflict> out = new ArrayList<>();
        checkRanges(m, out);
        checkAddressConflicts(m, out);
        checkLocks(m, out);
        Map<String, List<Edge>> graph = buildEffectGraph(m, out);
        checkCycles(graph, out);
        checkContradictions(graph, out);
        return out;
    }

    private void checkRanges(ChipModel m, List<Conflict> out) {
        for (RegisterDef r : m.registers()) {
            if (r.width() < 1 || r.width() > 64) {
                out.add(new Conflict("BAD_WIDTH", "register " + r.name() + " width " + r.width() + " out of range 1..64"));
            }
            List<BitField> fs = r.fields();
            for (int i = 0; i < fs.size(); i++) {
                BitField f = fs.get(i);
                if (f.offset() < 0 || f.width() < 1 || f.offset() + f.width() > r.width()) {
                    out.add(new Conflict("FIELD_OUT_OF_RANGE",
                            r.name() + "." + f.name() + " bits [" + f.offset() + ".." + (f.offset() + f.width() - 1)
                                    + "] exceed register width " + r.width()));
                }
                for (int j = i + 1; j < fs.size(); j++) {
                    BitField g = fs.get(j);
                    if (f.offset() < g.offset() + g.width() && g.offset() < f.offset() + f.width()) {
                        out.add(new Conflict("FIELD_OVERLAP",
                                r.name() + ": fields " + f.name() + " and " + g.name() + " overlap",
                                List.of(r.name() + "." + f.name(), r.name() + "." + g.name())));
                    }
                }
            }
        }
    }

    private void checkAddressConflicts(ChipModel m, List<Conflict> out) {
        List<RegisterDef> regs = m.registers();
        for (int i = 0; i < regs.size(); i++) {
            for (int j = i + 1; j < regs.size(); j++) {
                RegisterDef a = regs.get(i), b = regs.get(j);
                long aEnd = a.address() + (long) a.words() * 4;
                long bEnd = b.address() + (long) b.words() * 4;
                if (a.address() < bEnd && b.address() < aEnd) {
                    boolean related = related(m, a, b);
                    if (!related) {
                        out.add(new Conflict("ADDRESS_CONFLICT",
                                "registers " + a.name() + " and " + b.name() + " overlap in address space but are not aliases",
                                List.of(a.name(), b.name())));
                    }
                }
            }
        }
    }

    private boolean related(ChipModel m, RegisterDef a, RegisterDef b) {
        return m.resolveBase(a).name().equals(m.resolveBase(b).name());
    }

    private void checkLocks(ChipModel m, List<Conflict> out) {
        for (RegisterDef r : m.registers()) {
            if (r.lockedBy() != null) {
                RegisterDef lr = m.register(r.lockedBy().register());
                if (lr == null || lr.field(r.lockedBy().field()) == null) {
                    out.add(new Conflict("LOCK_TARGET_MISSING",
                            r.name() + " lockedBy references missing " + r.lockedBy().register() + "." + r.lockedBy().field()));
                }
            }
        }
    }

    private record Edge(String from, String to, SideEffect.Action action) {}

    private Map<String, List<Edge>> buildEffectGraph(ChipModel m, List<Conflict> out) {
        Map<String, List<Edge>> graph = new LinkedHashMap<>();
        for (RegisterDef r : m.registers()) {
            for (BitField f : r.fields()) {
                String from = r.name() + "." + f.name();
                for (SideEffect se : f.sideEffects()) {
                    RegisterDef tr = findRegister(m, se.targetRegister());
                    BitField tf = tr == null ? null : tr.field(se.targetField());
                    if (tf == null) {
                        out.add(new Conflict("EFFECT_TARGET_MISSING",
                                from + " side-effect targets missing " + se.targetKey()));
                        continue;
                    }
                    graph.computeIfAbsent(from, k -> new ArrayList<>())
                            .add(new Edge(from, se.targetKey(), se.action()));
                }
            }
        }
        return graph;
    }

    private void checkCycles(Map<String, List<Edge>> graph, List<Conflict> out) {
        Set<String> reported = new HashSet<>();
        for (String start : graph.keySet()) {
            List<String> cycle = shortestCycle(graph, start);
            if (cycle != null && reported.add(String.join(">", cycle))) {
                out.add(new Conflict("SIDE_EFFECT_CYCLE",
                        "side-effect cycle: " + String.join(" -> ", cycle), cycle));
            }
        }
    }

    /** BFS for the shortest path from start back to start. */
    private List<String> shortestCycle(Map<String, List<Edge>> graph, String start) {
        record Node(String key, List<String> path) {}
        ArrayDeque<Node> q = new ArrayDeque<>();
        q.add(new Node(start, List.of(start)));
        Set<String> visited = new HashSet<>();
        while (!q.isEmpty()) {
            Node n = q.poll();
            for (Edge e : graph.getOrDefault(n.key(), List.of())) {
                if (e.to().equals(start)) {
                    List<String> path = new ArrayList<>(n.path());
                    path.add(start);
                    return path;
                }
                if (visited.add(e.to())) {
                    List<String> path = new ArrayList<>(n.path());
                    path.add(e.to());
                    q.add(new Node(e.to(), path));
                }
            }
        }
        return null;
    }

    /**
     * A chain is contradictory when one write can both SET and CLEAR the same target.
     * BFS per source keeps the first (shortest) path per (target, action-class); the first
     * time a target is reached with the opposite class we have the shortest conflict path.
     */
    private void checkContradictions(Map<String, List<Edge>> graph, List<Conflict> out) {
        Set<String> reported = new HashSet<>();
        for (String source : graph.keySet()) {
            record Reach(String key, int cls, List<String> path) {}
            Map<String, Map<Integer, List<String>>> firstPath = new HashMap<>();
            ArrayDeque<Reach> q = new ArrayDeque<>();
            for (Edge e : graph.getOrDefault(source, List.of())) {
                q.add(new Reach(e.to(), cls(e.action()), List.of(source, e.to())));
            }
            while (!q.isEmpty()) {
                Reach r = q.poll();
                Map<Integer, List<String>> byClass = firstPath.computeIfAbsent(r.key(), k -> new HashMap<>());
                List<String> existing = byClass.get(r.cls());
                if (existing != null) continue; // already reached with same class, not shorter
                // opposite class present? -> contradiction, shortest by BFS order
                for (Map.Entry<Integer, List<String>> ent : byClass.entrySet()) {
                    if (ent.getKey() != r.cls() && contradicts(ent.getKey(), r.cls())) {
                        List<String> path = new ArrayList<>(r.path());
                        String sig = source + "|" + r.key();
                        if (reported.add(sig)) {
                            out.add(new Conflict("SIDE_EFFECT_CONTRADICTION",
                                    "write to " + source + " can both set and clear " + r.key()
                                            + " (paths: " + String.join(" -> ", ent.getValue())
                                            + "  vs  " + String.join(" -> ", path) + ")",
                                    path));
                        }
                    }
                }
                byClass.putIfAbsent(r.cls(), r.path());
                for (Edge e : graph.getOrDefault(r.key(), List.of())) {
                    if (r.path().contains(e.to())) continue; // avoid loops; cycles reported separately
                    List<String> path = new ArrayList<>(r.path());
                    path.add(e.to());
                    q.add(new Reach(e.to(), cls(e.action()), path));
                }
            }
        }
    }

    /** action class: 0 = set-ish, 1 = clear-ish, 2 = toggle (neutral) */
    private int cls(SideEffect.Action a) {
        return switch (a) {
            case SET -> 0;
            case CLEAR -> 1;
            case TOGGLE -> 2;
        };
    }

    private boolean contradicts(int a, int b) {
        return (a == 0 && b == 1) || (a == 1 && b == 0);
    }

    private RegisterDef findRegister(ChipModel m, String name) {
        return m.register(name);
    }
}
