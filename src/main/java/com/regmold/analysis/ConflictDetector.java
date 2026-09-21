package com.regmold.analysis;

import com.regmold.model.RegisterModel;
import com.regmold.model.SideEffect;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Detects contradictory and cyclic side-effect chains and reports the
 * shortest conflict path for each finding.
 */
public class ConflictDetector {

    public record Conflict(String kind, String message, List<String> path) {}

    public List<Conflict> detect(RegisterModel model) {
        List<Conflict> conflicts = new ArrayList<>();
        conflicts.addAll(detectContradictions(model));
        conflicts.addAll(detectCycles(model));
        return conflicts;
    }

    /**
     * A contradiction exists when one trigger condition can assign two
     * different values to the same target field, directly or through a
     * chain. BFS from each trigger yields the shortest conflict path.
     */
    private List<Conflict> detectContradictions(RegisterModel model) {
        List<Conflict> out = new ArrayList<>();
        Map<String, List<SideEffect>> byTrigger = new LinkedHashMap<>();
        for (SideEffect e : model.sideEffects()) {
            byTrigger.computeIfAbsent(e.triggerReg() + "." + e.triggerField()
                    + "==" + e.triggerValue(), k -> new ArrayList<>()).add(e);
        }
        for (Map.Entry<String, List<SideEffect>> entry : byTrigger.entrySet()) {
            // BFS over the effect graph starting from each direct effect.
            Map<String, Long> assigned = new HashMap<>();
            Map<String, List<String>> assignPath = new HashMap<>();
            Deque<SideEffect> queue = new ArrayDeque<>(entry.getValue());
            Map<SideEffect, List<String>> effectPath = new HashMap<>();
            for (SideEffect e : entry.getValue()) {
                effectPath.put(e, List.of(entry.getKey() + " => " + e.describe()));
            }
            while (!queue.isEmpty()) {
                SideEffect e = queue.poll();
                String key = e.targetReg() + "." + e.targetField();
                List<String> path = effectPath.get(e);
                if (assigned.containsKey(key)) {
                    if (assigned.get(key) != e.targetValue()) {
                        List<String> conflictPath = new ArrayList<>(assignPath.get(key));
                        conflictPath.add("... conflicts with ...");
                        conflictPath.addAll(path);
                        out.add(new Conflict("CONTRADICTION",
                                "contradictory side-effect chain on " + key
                                        + ": values " + assigned.get(key) + " and " + e.targetValue(),
                                shortest(conflictPath)));
                        break;
                    }
                    continue;
                }
                assigned.put(key, e.targetValue());
                assignPath.put(key, path);
                for (SideEffect next : model.sideEffects()) {
                    if (next.triggerReg().equals(e.targetReg())
                            && next.triggerField().equals(e.targetField())
                            && next.triggerValue() == e.targetValue()) {
                        List<String> nextPath = new ArrayList<>(path);
                        nextPath.add(next.describe());
                        effectPath.put(next, nextPath);
                        queue.add(next);
                    }
                }
            }
        }
        return out;
    }

    /** Finds the shortest cycle in the trigger-to-target effect graph. */
    private List<Conflict> detectCycles(RegisterModel model) {
        Map<String, List<String>> graph = new LinkedHashMap<>();
        Map<String, String> edgeLabel = new HashMap<>();
        for (SideEffect e : model.sideEffects()) {
            String from = e.triggerReg() + "." + e.triggerField();
            String to = e.targetReg() + "." + e.targetField();
            graph.computeIfAbsent(from, k -> new ArrayList<>()).add(to);
            edgeLabel.put(from + "->" + to, e.describe());
        }
        List<String> best = null;
        for (String start : graph.keySet()) {
            // BFS for shortest path back to start.
            Deque<List<String>> queue = new ArrayDeque<>();
            queue.add(List.of(start));
            while (!queue.isEmpty()) {
                List<String> path = queue.poll();
                String tail = path.get(path.size() - 1);
                for (String next : graph.getOrDefault(tail, List.of())) {
                    if (next.equals(start) && path.size() >= 1) {
                        List<String> cycle = new ArrayList<>(path);
                        cycle.add(start);
                        if (best == null || cycle.size() < best.size()) {
                            best = cycle;
                        }
                    } else if (!path.contains(next) && path.size() < graph.size() + 1) {
                        List<String> extended = new ArrayList<>(path);
                        extended.add(next);
                        queue.add(extended);
                    }
                }
            }
        }
        if (best == null) {
            return List.of();
        }
        List<String> labeled = new ArrayList<>();
        for (int i = 0; i + 1 < best.size(); i++) {
            labeled.add(edgeLabel.getOrDefault(best.get(i) + "->" + best.get(i + 1),
                    best.get(i) + " -> " + best.get(i + 1)));
        }
        return List.of(new Conflict("CYCLE",
                "cyclic side-effect chain: " + String.join(" -> ", best), labeled));
    }

    private static List<String> shortest(List<String> path) {
        return path;
    }
}
