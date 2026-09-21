package com.regforge.validate;

import com.regforge.model.AccessMode;
import com.regforge.model.ChipModel;
import com.regforge.model.FieldDef;
import com.regforge.model.RegisterDef;
import com.regforge.model.SideEffect;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class Validator {

    public List<Diagnostic> validate(ChipModel model) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        List<RegisterDef> all = model.allRegisters();
        Map<String, RegisterDef> byName = new LinkedHashMap<>();
        for (RegisterDef reg : all) {
            if (byName.put(reg.name(), reg) != null) {
                diagnostics.add(Diagnostic.error("DUP_REGISTER",
                        "寄存器重名: " + reg.name(), List.of(reg.name())));
            }
        }

        for (RegisterDef reg : all) {
            checkRegister(model, reg, diagnostics);
        }

        checkAddressing(model, byName, diagnostics);
        checkSideEffects(byName, diagnostics);
        checkLocks(byName, diagnostics);
        diagnostics.sort((a, b) -> {
            int c = a.severity().compareTo(b.severity());
            if (c != 0) {
                return c;
            }
            c = a.code().compareTo(b.code());
            return c != 0 ? c : String.join(".", a.path()).compareTo(String.join(".", b.path()));
        });
        return diagnostics;
    }

    public boolean valid(List<Diagnostic> diagnostics) {
        return diagnostics.stream().noneMatch(d -> d.severity() == Diagnostic.Severity.ERROR);
    }

    private void checkRegister(ChipModel model, RegisterDef reg, List<Diagnostic> out) {
        long resetMask = reg.width() >= 64 ? ~0L : ((1L << reg.width()) - 1);
        if ((reg.reset() & ~resetMask) != 0) {
            out.add(Diagnostic.error("RESET_OUT_OF_RANGE",
                    "寄存器 " + reg.name() + " 复位值超出 " + reg.width() + " 位宽度",
                    List.of(reg.name())));
        }
        FieldDef previous = null;
        for (FieldDef field : reg.fields()) {
            if (field.msb() >= reg.width()) {
                out.add(Diagnostic.error("FIELD_OUT_OF_RANGE",
                        "位域 " + reg.name() + "." + field.name() + " 的最高位 "
                                + field.msb() + " 超出寄存器宽度 " + reg.width(),
                        List.of(reg.name(), field.name())));
            }
            if (previous != null && previous.overlaps(field)) {
                out.add(Diagnostic.error("FIELD_OVERLAP",
                        "位域重叠: " + reg.name() + "." + previous.name()
                                + " [" + previous.msb() + ":" + previous.lsb() + "] 与 "
                                + field.name() + " [" + field.msb() + ":" + field.lsb() + "]",
                        List.of(reg.name(), previous.name(), field.name())));
            }
            previous = field;
        }
        if (reg.width() > 32 && reg.width() != 64) {
            out.add(Diagnostic.warning("UNALIGNED_MULTIWORD",
                    "寄存器 " + reg.name() + " 宽度 " + reg.width()
                            + " 不是 32 的整数倍，多字读取次序以 32 位字划分",
                    List.of(reg.name())));
        }
    }

    private void checkAddressing(ChipModel model, Map<String, RegisterDef> byName,
                                 List<Diagnostic> out) {
        for (com.regforge.model.AddressSpace space : model.addressSpaces()) {
            List<RegisterDef> regs = new ArrayList<>(space.registers());
            for (int i = 0; i < regs.size(); i++) {
                RegisterDef a = regs.get(i);
                if (a.isAlias()) {
                    RegisterDef target = byName.get(a.aliasOf());
                    if (target == null) {
                        out.add(Diagnostic.error("ALIAS_TARGET_MISSING",
                                "别名寄存器 " + a.name() + " 指向不存在的寄存器 " + a.aliasOf(),
                                List.of(a.name())));
                    } else if (target.isAlias()) {
                        out.add(Diagnostic.error("ALIAS_CHAIN",
                                "别名寄存器 " + a.name() + " 不能再指向别名 " + target.name(),
                                List.of(a.name(), target.name())));
                    } else if (target.address() != a.address()
                            || target.width() != a.width()) {
                        out.add(Diagnostic.error("ALIAS_GEOMETRY_MISMATCH",
                                "别名 " + a.name() + " 与 " + target.name()
                                        + " 的地址/宽度必须一致",
                                List.of(a.name(), target.name())));
                    } else {
                        reportAliasSemantics(a, target, out);
                    }
                }
                for (int j = i + 1; j < regs.size(); j++) {
                    RegisterDef b = regs.get(j);
                    long aEnd = a.address() + Math.max(1, a.width() / 8) - 1;
                    long bEnd = b.address() + Math.max(1, b.width() / 8) - 1;
                    if (a.address() > bEnd || b.address() > aEnd) {
                        continue;
                    }
                    boolean aliased = pointsAt(a, b) || pointsAt(b, a);
                    if (!aliased) {
                        out.add(Diagnostic.error("ADDRESS_COLLISION",
                                "寄存器 " + a.name() + " 与 " + b.name()
                                        + " 地址区间重叠且未声明 aliasOf",
                                List.of(a.name(), b.name())));
                    }
                }
            }
        }
    }

    private boolean pointsAt(RegisterDef a, RegisterDef b) {
        return a.isAlias() && a.aliasOf().equals(b.name());
    }

    private void reportAliasSemantics(RegisterDef alias, RegisterDef target,
                                      List<Diagnostic> out) {
        for (FieldDef af : alias.fields()) {
            for (FieldDef tf : target.fields()) {
                if (!af.overlaps(tf)) {
                    continue;
                }
                if (af.access() != tf.access()) {
                    out.add(Diagnostic.info("ALIAS_SEMANTICS",
                            "别名写语义不同: " + alias.name() + "." + af.name()
                                    + " 为 " + af.access() + "，" + target.name() + "." + tf.name()
                                    + " 为 " + tf.access() + "（允许，审阅时留意）",
                            List.of(alias.name(), af.name(), target.name(), tf.name())));
                }
                if (af.access() == AccessMode.RESERVED && tf.access() != AccessMode.RESERVED) {
                    out.add(Diagnostic.warning("ALIAS_RESERVED_LEAK",
                            "别名 " + alias.name() + "." + af.name() + " 标记保留，"
                                    + "但基寄存器对应位域 " + target.name() + "." + tf.name() + " 可写",
                            List.of(alias.name(), af.name(), target.name(), tf.name())));
                }
            }
        }
    }

    private record Edge(String from, SideEffect effect) {
    }

    private void checkSideEffects(Map<String, RegisterDef> byName, List<Diagnostic> out) {
        // 节点 = REG.FIELD，边 = 副作用触发
        Map<String, Set<String>> graph = new LinkedHashMap<>();
        Map<String, List<Edge>> outgoing = new LinkedHashMap<>();
        for (RegisterDef reg : byName.values()) {
            for (FieldDef f : reg.fields()) {
                graph.putIfAbsent(reg.name() + "." + f.name(), new HashSet<>());
            }
        }
        for (RegisterDef reg : byName.values()) {
            for (SideEffect effect : reg.sideEffects()) {
                String from = reg.name() + "." + effect.triggerField();
                RegisterDef targetReg = byName.get(effect.targetRegister());
                FieldDef trigger = reg.field(effect.triggerField());
                if (trigger == null) {
                    out.add(Diagnostic.error("EFFECT_TRIGGER_MISSING",
                            "寄存器 " + reg.name() + " 的副作用引用了不存在的触发位域 "
                                    + effect.triggerField(),
                            List.of(reg.name(), effect.triggerField())));
                    continue;
                }
                if (targetReg == null) {
                    out.add(Diagnostic.error("EFFECT_TARGET_REGISTER_MISSING",
                            "副作用 " + from + " 指向不存在的寄存器 "
                                    + effect.targetRegister(),
                            List.of(reg.name(), effect.triggerField(), effect.targetRegister())));
                    continue;
                }
                FieldDef targetField = targetReg.field(effect.targetField());
                if (targetField == null) {
                    out.add(Diagnostic.error("EFFECT_TARGET_FIELD_MISSING",
                            "副作用 " + from + " 指向不存在的位域 " + effect.node(),
                            List.of(reg.name(), effect.triggerField(),
                                    effect.targetRegister(), effect.targetField())));
                    continue;
                }
                graph.computeIfAbsent(from, k -> new HashSet<>()).add(effect.node());
                outgoing.computeIfAbsent(from, k -> new ArrayList<>())
                        .add(new Edge(from, effect));
            }
        }

        detectConflictingEffects(outgoing, out);
        detectCycles(graph, out);
    }

    private void detectConflictingEffects(Map<String, List<Edge>> outgoing,
                                          List<Diagnostic> out) {
        // 同一触发源对同一目标位域产生不可调和的不同动作
        for (Map.Entry<String, List<Edge>> entry : outgoing.entrySet()) {
            Map<String, List<Edge>> byTarget = new LinkedHashMap<>();
            for (Edge edge : entry.getValue()) {
                byTarget.computeIfAbsent(edge.effect().node(), k -> new ArrayList<>()).add(edge);
            }
            for (List<Edge> edges : byTarget.values()) {
                boolean sameAction = edges.stream().allMatch(e ->
                        e.effect().action() == edges.get(0).effect().action()
                                && java.util.Objects.equals(e.effect().value(),
                                        edges.get(0).effect().value()));
                if (!sameAction) {
                    String desc = edges.stream()
                            .map(e -> e.effect().action()
                                    + (e.effect().value() == null ? "" : "=" + e.effect().value()))
                            .collect(Collectors.joining(" vs "));
                    SideEffect first = edges.get(0).effect();
                    out.add(Diagnostic.error("SIDE_EFFECT_CONFLICT",
                            "相互矛盾的副作用: " + entry.getKey() + " → "
                                    + first.node() + " 同时要求 " + desc,
                            List.of(entry.getKey(), first.node())));
                }
            }
        }
    }

    /**
     * BFS 求每个节点回到自身的最短环，按规范旋转去重。
     */
    private void detectCycles(Map<String, Set<String>> graph, List<Diagnostic> out) {
        Set<String> reported = new HashSet<>();
        for (String start : graph.keySet()) {
            List<String> cycle = shortestCycleThrough(graph, start);
            if (cycle != null) {
                List<String> canonical = canonicalRotation(cycle);
                String key = String.join("->", canonical);
                if (reported.add(key)) {
                    String rendered = String.join(" → ", cycle);
                    out.add(Diagnostic.error("SIDE_EFFECT_CYCLE",
                            "检测到相互矛盾的副作用环（最短冲突路径，长度 "
                                    + (cycle.size() - 1) + "）: " + rendered,
                            cycle));
                }
            }
        }
    }

    private List<String> shortestCycleThrough(Map<String, Set<String>> graph, String start) {
        Map<String, String> predecessor = new HashMap<>();
        Deque<String> queue = new ArrayDeque<>();
        for (String next : graph.getOrDefault(start, Set.of())) {
            if (next.equals(start)) {
                return List.of(start, start);
            }
            if (!predecessor.containsKey(next)) {
                predecessor.put(next, start);
                queue.add(next);
            }
        }
        while (!queue.isEmpty()) {
            String node = queue.poll();
            for (String next : graph.getOrDefault(node, Set.of())) {
                if (next.equals(start)) {
                    List<String> back = new ArrayList<>();
                    String cur = node;
                    back.add(cur);
                    while (!cur.equals(start)) {
                        cur = predecessor.get(cur);
                        back.add(cur);
                    }
                    java.util.Collections.reverse(back);
                    back.add(start);
                    return back;
                }
                if (!predecessor.containsKey(next)) {
                    predecessor.put(next, node);
                    queue.add(next);
                }
            }
        }
        return null;
    }

    private List<String> canonicalRotation(List<String> cycle) {
        // cycle 首尾相同，去掉尾再找最小旋转
        List<String> nodes = new ArrayList<>(cycle.subList(0, cycle.size() - 1));
        int min = 0;
        for (int i = 1; i < nodes.size(); i++) {
            if (nodes.get(i).compareTo(nodes.get(min)) < 0) {
                min = i;
            }
        }
        List<String> rotated = new ArrayList<>();
        for (int i = 0; i < nodes.size(); i++) {
            rotated.add(nodes.get((min + i) % nodes.size()));
        }
        rotated.add(rotated.get(0));
        return rotated;
    }

    private void checkLocks(Map<String, RegisterDef> byName, List<Diagnostic> out) {
        for (RegisterDef reg : byName.values()) {
            if (reg.lockedBy() == null) {
                continue;
            }
            String[] parts = reg.lockedBy().split("\\.", 2);
            if (parts.length != 2 || byName.get(parts[0]) == null
                    || byName.get(parts[0]).field(parts[1]) == null) {
                out.add(Diagnostic.error("LOCK_TARGET_MISSING",
                        "寄存器 " + reg.name() + " 的 lockedBy 指向不存在的位域 "
                                + reg.lockedBy(),
                        List.of(reg.name(), reg.lockedBy())));
                continue;
            }
            if (reg.unlockValue() != null) {
                RegisterDef lockReg = byName.get(parts[0]);
                FieldDef lockField = lockReg.field(parts[1]);
                long fieldMax = lockField.mask() >>> lockField.lsb();
                if (Long.compareUnsigned(reg.unlockValue(), fieldMax) > 0) {
                    out.add(Diagnostic.error("UNLOCK_VALUE_OUT_OF_RANGE",
                            "寄存器 " + reg.name() + " 的 unlockValue "
                                    + Long.toUnsignedString(reg.unlockValue())
                                    + " 超出锁位域 " + reg.lockedBy() + " 容量",
                            List.of(reg.name(), reg.lockedBy())));
                }
            }
        }
    }
}
