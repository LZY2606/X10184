package com.regmold.validate;

import com.regmold.domain.EffectModel;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class EffectCycleDetector {

    public static class Cycle {
        public final List<String> path;
        public final String effect;

        public Cycle(List<String> path, String effect) {
            this.path = path;
            this.effect = effect;
        }
    }

    public static Cycle findShortestCycle(ModelIndex index) {
        Map<String, List<EffectModel>> outgoing = new HashMap<>();
        for (EffectModel e : index.model.effects) {
            outgoing.computeIfAbsent(e.triggerRegister, k -> new ArrayList<>()).add(e);
        }
        Cycle best = null;
        Set<String> nodes = new LinkedHashSet<>();
        for (EffectModel e : index.model.effects) {
            nodes.add(e.triggerRegister);
            nodes.add(e.targetRegister);
        }
        for (String start : nodes) {
            Cycle c = bfs(start, outgoing);
            if (c != null && (best == null || c.path.size() < best.path.size())) {
                best = c;
            }
        }
        return best;
    }

    private static Cycle bfs(String start, Map<String, List<EffectModel>> outgoing) {
        Map<String, String> prevNode = new HashMap<>();
        Map<String, String> prevEffect = new HashMap<>();
        Deque<String> queue = new ArrayDeque<>();
        Set<String> visited = new LinkedHashSet<>();
        queue.add(start);
        visited.add(start);
        while (!queue.isEmpty()) {
            String node = queue.poll();
            for (EffectModel e : outgoing.getOrDefault(node, List.of())) {
                String next = e.targetRegister;
                if (next.equals(start)) {
                    List<String> path = new ArrayList<>();
                    collectPath(prevNode, start, node, path);
                    java.util.Collections.reverse(path);
                    path.add(start);
                    return new Cycle(path, e.name);
                }
                if (visited.add(next)) {
                    prevNode.put(next, node);
                    prevEffect.put(next, e.name);
                    queue.add(next);
                }
            }
        }
        return null;
    }

    private static void collectPath(Map<String, String> prev, String start, String node, List<String> out) {
        String cur = node;
        while (cur != null && !cur.equals(start)) {
            out.add(cur);
            cur = prev.get(cur);
        }
        if (cur != null) {
            out.add(start);
        }
    }
}
