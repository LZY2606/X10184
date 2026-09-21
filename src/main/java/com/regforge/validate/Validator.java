package com.regforge.validate;

import com.regforge.model.Field;
import com.regforge.model.FieldEffect;
import com.regforge.model.Register;
import com.regforge.model.RegisterModel;
import org.springframework.stereotype.Component;

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
 * Structural and semantic validation.
 *
 * <p>Beyond overlap detection it resolves alias/lock references, verifies
 * cross-word fields and finds contradictory side-effect chains.  A chain is
 * contradictory when it is cyclic (never settles) or when two fields write a
 * different action onto the same target; in both cases BFS returns the
 * shortest conflicting path.
 */
@Component
public class Validator {

    public List<Issue> validate(RegisterModel model) {
        List<Issue> issues = new ArrayList<>();
        checkNames(model, issues);
        checkFields(model, issues);
        checkAliasesAndLocks(model, issues);
        checkAddresses(model, issues);
        checkEffects(model, issues);
        return issues;
    }

    private void checkNames(RegisterModel model, List<Issue> issues) {
        Set<String> regNames = new HashSet<>();
        for (Register r : model.getRegisters()) {
            if (!r.getName().isBlank() && !regNames.add(r.getName())) {
                issues.add(Issue.error("DUP_REG_NAME", "寄存器名重复: " + r.getName(), List.of(r.getName())));
            }
        }
        for (Register r : model.getRegisters()) {
            Set<String> fieldNames = new HashSet<>();
            for (Field f : r.getFields()) {
                if (!f.getName().isBlank() && !fieldNames.add(f.getName())) {
                    issues.add(Issue.error("DUP_FIELD_NAME",
                            "寄存器 " + r.getName() + " 中位域名重复: " + f.getName(),
                            List.of(r.getName(), f.getName())));
                }
            }
        }
    }

    private void checkFields(RegisterModel model, List<Issue> issues) {
        for (Register r : model.getRegisters()) {
            List<String> rp = List.of(r.getName());
            if (r.getWidthBits() <= 0 || r.getWidthBits() % model.getWordBits() != 0) {
                issues.add(Issue.error("REG_WIDTH",
                        "寄存器 " + r.getName() + " 宽度 " + r.getWidthBits()
                                + " 必须是正的字宽倍数 (" + model.getWordBits() + ")", rp));
            }
            for (Field f : r.getFields()) {
                List<String> fp = List.of(r.getName(), f.getName());
                if (f.getLsb() < 0 || f.getMsb() < f.getLsb()) {
                    issues.add(Issue.error("FIELD_RANGE", "位域范围非法: " + f.getLsb() + ".." + f.getMsb(), fp));
                }
                if (f.getMsb() >= r.getWidthBits()) {
                    issues.add(Issue.error("FIELD_OUT_OF_BOUNDS",
                            "位域 " + f.getName() + " 越界 (msb=" + f.getMsb()
                                    + ", 寄存器宽度=" + r.getWidthBits() + ")", fp));
                }
                long maxVal = f.width() >= 64 ? -1L : (1L << f.width()) - 1L;
                if (Long.compareUnsigned(f.getReset(), maxVal) > 0) {
                    issues.add(Issue.error("FIELD_RESET",
                            "位域 " + f.getName() + " 复位值超出位宽", fp));
                }
                if (r.isMultiWord(model.getWordBits())
                        && f.getLsb() / model.getWordBits() != f.getMsb() / model.getWordBits()) {
                    issues.add(Issue.warning("CROSS_WORD_FIELD",
                            "位域 " + f.getName() + " 跨字 (字索引 "
                                    + f.getLsb() / model.getWordBits() + ".." + f.getMsb() / model.getWordBits()
                                    + ")，注意读取次序 " + r.getReadOrder(), fp));
                }
            }
            // overlaps, reserved-vs-anything and double-coverage
            for (int i = 0; i < r.getFields().size(); i++) {
                for (int j = i + 1; j < r.getFields().size(); j++) {
                    Field a = r.getFields().get(i);
                    Field b = r.getFields().get(j);
                    int lo = Math.max(a.getLsb(), b.getLsb());
                    int hi = Math.min(a.getMsb(), b.getMsb());
                    if (lo <= hi) {
                        boolean reserved = a.getAccess().name().equals("RESERVED") || b.getAccess().name().equals("RESERVED");
                        String code = reserved ? "RESERVED_OVERLAP" : "FIELD_OVERLAP";
                        issues.add(Issue.error(code,
                                "位域重叠: " + a.getName() + " 与 " + b.getName() + " 共享位 " + lo + ".." + hi,
                                List.of(r.getName(), a.getName(), b.getName())));
                    }
                }
            }
        }
    }

    private void checkAliasesAndLocks(RegisterModel model, List<Issue> issues) {
        for (Register r : model.getRegisters()) {
            if (r.isAlias()) {
                Register target = model.findRegister(r.getAliasOf());
                if (target == null) {
                    issues.add(Issue.error("ALIAS_TARGET_MISSING",
                            "别名寄存器 " + r.getName() + " 指向不存在的 " + r.getAliasOf(),
                            List.of(r.getName())));
                } else if (target.isAlias()) {
                    issues.add(Issue.error("ALIAS_CHAIN",
                            "别名寄存器 " + r.getName() + " 不能指向另一个别名 " + target.getName(),
                            List.of(r.getName())));
                }
            }
            if (r.getSetAlias() != null || r.getClrAlias() != null) {
                checkRef(model, r.getSetAlias(), r, "set_alias", issues);
                checkRef(model, r.getClrAlias(), r, "clr_alias", issues);
            }
            if (r.getLockedBy() != null) {
                String lockName = r.getLockedBy().contains(".")
                        ? r.getLockedBy().substring(0, r.getLockedBy().indexOf('.'))
                        : r.getLockedBy();
                if (model.findRegister(lockName) == null) {
                    issues.add(Issue.error("LOCK_MISSING",
                            "寄存器 " + r.getName() + " 的锁 " + r.getLockedBy() + " 不存在",
                            List.of(r.getName())));
                }
            }
        }
    }

    private void checkRef(RegisterModel model, String ref, Register owner, String kind, List<Issue> issues) {
        if (ref == null) return;
        String regName = ref.contains(".") ? ref.substring(0, ref.indexOf('.')) : ref;
        if (model.findRegister(regName) == null) {
            issues.add(Issue.error("REF_MISSING",
                    "寄存器 " + owner.getName() + " 的 " + kind + " 指向不存在的 " + ref,
                    List.of(owner.getName())));
        }
    }

    private void checkAddresses(RegisterModel model, List<Issue> issues) {
        // physical aliases share an address intentionally; everything else may not.
        Map<Long, List<Register>> byAddr = new LinkedHashMap<>();
        for (Register r : model.getRegisters()) {
            byAddr.computeIfAbsent(r.getAddress(), k -> new ArrayList<>()).add(r);
        }
        for (Map.Entry<Long, List<Register>> e : byAddr.entrySet()) {
            List<Register> rs = e.getValue();
            if (rs.size() < 2) continue;
            boolean allAlias = rs.stream().allMatch(x -> x.isAlias()
                    || rs.stream().anyMatch(y -> y != x && x.getAliasOf() != null
                            && x.getAliasOf().equals(y.getName())));
            if (!allAlias) {
                List<String> names = rs.stream().map(Register::getName).toList();
                issues.add(Issue.error("ADDR_CONFLICT",
                        "地址 " + Long.toUnsignedString(e.getKey(), 16)
                                + " 被多个非别名寄存器占用: " + String.join(", ", names),
                        names));
            }
        }
    }

    /** Graph: node = "reg.field" (or "reg"); edge = write effect. */
    private void checkEffects(RegisterModel model, List<Issue> issues) {
        Map<String, List<FieldEffect>> graph = new LinkedHashMap<>();
        Set<String> nodes = new HashSet<>();
        for (Register r : model.getRegisters()) {
            for (Field f : r.getFields()) {
                String src = r.getName() + "." + f.getName();
                nodes.add(src);
                for (FieldEffect eff : f.getEffects()) {
                    graph.computeIfAbsent(src, k -> new ArrayList<>()).add(eff);
                    nodes.add(eff.target());
                }
            }
        }
        // target existence
        for (var en : graph.entrySet()) {
            for (FieldEffect eff : en.getValue()) {
                if (!resolveTarget(model, eff.target())) {
                    issues.add(Issue.error("EFFECT_TARGET_MISSING",
                            en.getKey() + " 的副作用目标不存在: " + eff.target(),
                            List.of(en.getKey())));
                }
            }
        }

        // contradictory actions converging on the same target
        Map<String, String> actionByTarget = new HashMap<>();
        for (var en : graph.entrySet()) {
            for (FieldEffect eff : en.getValue()) {
                if (eff.isLatch()) continue;
                String prev = actionByTarget.put(eff.target(), eff.normalizedAction());
                if (prev != null && !prev.equals(eff.normalizedAction())) {
                    issues.add(Issue.error("CONFLICTING_EFFECT",
                            "目标 " + eff.target() + " 同时被 " + prev + " 与 "
                                    + eff.normalizedAction() + " 驱动",
                            List.of(eff.target(), en.getKey())));
                }
            }
        }

        // cycles: BFS shortest path from each node back to itself
        for (String start : nodes) {
            List<String> cyc = shortestCycle(graph, start);
            if (cyc != null) {
                issues.add(Issue.error("SIDE_EFFECT_CYCLE",
                        "检测到循环副作用链: " + String.join(" -> ", cyc), cyc));
                break; // one representative shortest cycle is enough
            }
        }
    }

    private boolean resolveTarget(RegisterModel model, String target) {
        if (target == null || target.isBlank()) return false;
        if (!target.contains(".")) return model.findRegister(target) != null;
        String rn = target.substring(0, target.indexOf('.'));
        String fn = target.substring(target.indexOf('.') + 1);
        Register r = model.findRegister(rn);
        if (r == null) return false;
        return r.getFields().stream().anyMatch(f -> f.getName().equals(fn));
    }

    /** BFS for the shortest directed cycle that starts and ends at {@code start}. */
    private List<String> shortestCycle(Map<String, List<FieldEffect>> graph, String start) {
        record Fringe(String node, List<String> path) {}
        Deque<Fringe> queue = new ArrayDeque<>();
        queue.add(new Fringe(start, new ArrayList<>(List.of(start))));
        Set<String> visited = new HashSet<>();
        visited.add(start);
        while (!queue.isEmpty()) {
            Fringe cur = queue.poll();
            for (FieldEffect eff : graph.getOrDefault(cur.node, List.of())) {
                List<String> nextPath = new ArrayList<>(cur.path);
                nextPath.add(eff.target());
                if (eff.target().equals(start)) {
                    return nextPath;
                }
                if (visited.add(eff.target())) {
                    queue.add(new Fringe(eff.target(), nextPath));
                }
            }
        }
        return null;
    }
}
