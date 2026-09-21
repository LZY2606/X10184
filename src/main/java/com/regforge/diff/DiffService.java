package com.regforge.diff;

import com.regforge.codegen.CGenerator;
import com.regforge.model.AccessMode;
import com.regforge.model.ChipModel;
import com.regforge.model.FieldDef;
import com.regforge.model.RegisterDef;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 比较两个模型版本：ABI 差异（地址/端序/宽度变化）必须列出受影响的生成 API，
 * 行为差异聚焦访问模式、复位值、锁存与副作用。
 */
public class DiffService {

    public ModelDiff diff(ChipModel from, ChipModel to, String fromHash, String toHash) {
        Map<String, RegisterDef> a = map(from);
        Map<String, RegisterDef> b = map(to);
        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        List<ModelDiff.ApiChange> abi = new ArrayList<>();
        List<String> behavior = new ArrayList<>();

        for (String name : b.keySet()) {
            if (!a.containsKey(name)) {
                added.add(name);
            }
        }
        for (String name : a.keySet()) {
            if (!b.containsKey(name)) {
                removed.add(name);
            }
        }
        for (String name : a.keySet()) {
            RegisterDef ra = a.get(name);
            RegisterDef rb = b.get(name);
            if (rb == null) {
                continue;
            }
            compareRegister(from.name(), ra, rb, abi, behavior);
        }
        if (!from.endianness().equals(to.endianness())) {
            List<String> allApis = new ArrayList<>();
            for (String name : a.keySet()) {
                if (b.containsKey(name)) {
                    allApis.addAll(accessApis(from.name(), name));
                }
            }
            abi.add(new ModelDiff.ApiChange("*", "ENDIANNESS",
                    "端序 " + from.endianness() + " → " + to.endianness(), allApis));
        }
        return new ModelDiff(fromHash, toHash,
                List.copyOf(added), List.copyOf(removed),
                List.copyOf(abi), List.copyOf(behavior));
    }

    private void compareRegister(String chip, RegisterDef a, RegisterDef b,
                                 List<ModelDiff.ApiChange> abi, List<String> behavior) {
        if (a.address() != b.address()) {
            abi.add(new ModelDiff.ApiChange(a.name(), "ADDRESS",
                    "地址 " + CGenerator.hex(a.address()) + " → " + CGenerator.hex(b.address()),
                    List.of(offsetDefine(chip, a.name()),
                            accessor(chip, a.name(), "read"),
                            accessor(chip, a.name(), "write"))));
        }
        if (a.width() != b.width()) {
            abi.add(new ModelDiff.ApiChange(a.name(), "WIDTH",
                    "宽度 " + a.width() + " → " + b.width(),
                    List.of(define(chip, a.name(), "WIDTH"),
                            accessor(chip, a.name(), "read"),
                            accessor(chip, a.name(), "write"),
                            define(chip, a.name(), "RESET"))));
        }
        if (a.wordOrder() != b.wordOrder()) {
            abi.add(new ModelDiff.ApiChange(a.name(), "WORD_ORDER",
                    "多字读取次序 " + a.wordOrder() + " → " + b.wordOrder(),
                    List.of(accessor(chip, a.name(), "read"),
                            accessor(chip, a.name(), "write"))));
        }
        if (a.reset() != b.reset()) {
            behavior.add("复位值变化: " + a.name() + " "
                    + CGenerator.hex(a.reset()) + " → " + CGenerator.hex(b.reset())
                    + "（影响 " + define(chip, a.name(), "RESET") + "）");
        }
        if (!eq(a.lockedBy(), b.lockedBy()) || !eq(a.unlockValue(), b.unlockValue())) {
            behavior.add("锁存关系变化: " + a.name() + " lockedBy "
                    + a.lockedBy() + "(unlock=" + a.unlockValue() + ") → "
                    + b.lockedBy() + "(unlock=" + b.unlockValue() + ")");
        }
        if (!eq(a.aliasOf(), b.aliasOf())) {
            abi.add(new ModelDiff.ApiChange(a.name(), "ALIAS",
                    "别名关系 " + a.aliasOf() + " → " + b.aliasOf(),
                    List.of(accessor(chip, a.name(), "read"),
                            accessor(chip, a.name(), "write"))));
        }

        Map<String, FieldDef> fa = new LinkedHashMap<>();
        Map<String, FieldDef> fb = new LinkedHashMap<>();
        a.fields().forEach(f -> fa.put(f.name(), f));
        b.fields().forEach(f -> fb.put(f.name(), f));
        List<String> newFields = new ArrayList<>();
        List<String> goneFields = new ArrayList<>();
        for (String f : fb.keySet()) {
            if (!fa.containsKey(f)) {
                newFields.add(f);
            }
        }
        for (String f : fa.keySet()) {
            if (!fb.containsKey(f)) {
                goneFields.add(f);
            }
        }
        for (String f : newFields) {
            behavior.add("新增位域: " + a.name() + "." + f + " ["
                    + fb.get(f).msb() + ":" + fb.get(f).lsb() + "] " + fb.get(f).access());
            abi.add(new ModelDiff.ApiChange(a.name(), "FIELD_ADDED",
                    "新增位域 " + f, List.of(
                            define(chip, a.name(), f + "_MASK"),
                            define(chip, a.name(), f + "_SHIFT"))));
        }
        for (String f : goneFields) {
            behavior.add("删除位域: " + a.name() + "." + f);
            abi.add(new ModelDiff.ApiChange(a.name(), "FIELD_REMOVED",
                    "删除位域 " + f, List.of(
                            define(chip, a.name(), f + "_MASK"),
                            define(chip, a.name(), f + "_SHIFT"))));
        }
        for (String f : fa.keySet()) {
            FieldDef x = fa.get(f);
            FieldDef y = fb.get(f);
            if (y == null) {
                continue;
            }
            if (x.lsb() != y.lsb() || x.msb() != y.msb()) {
                abi.add(new ModelDiff.ApiChange(a.name(), "FIELD_LAYOUT",
                        "位域布局变化 " + f + " [" + x.msb() + ":" + x.lsb() + "] → ["
                                + y.msb() + ":" + y.lsb() + "]",
                        List.of(define(chip, a.name(), f + "_MASK"),
                                define(chip, a.name(), f + "_SHIFT"),
                                accessor(chip, a.name(), f + "_get"))));
            }
            if (x.access() != y.access()) {
                behavior.add("访问语义变化: " + a.name() + "." + f + " "
                        + x.access() + " → " + y.access());
                List<String> apis = new ArrayList<>(List.of(
                        accessor(chip, a.name(), f + "_get")));
                if (x.access() == AccessMode.RW || y.access() == AccessMode.RW
                        || x.access() == AccessMode.W1C || y.access() == AccessMode.W1C
                        || x.access() == AccessMode.W1S || y.access() == AccessMode.W1S) {
                    apis.add(accessor(chip, a.name(), f + "_set"));
                    apis.add(accessor(chip, a.name(), f + "_clear"));
                }
                abi.add(new ModelDiff.ApiChange(a.name(), "FIELD_ACCESS",
                        "访问模式 " + f + ": " + x.access() + " → " + y.access(), apis));
            }
        }

        List<String> ea = effectSignatures(a);
        List<String> eb = effectSignatures(b);
        for (String e : eb) {
            if (!ea.contains(e)) {
                behavior.add("新增副作用: " + a.name() + " " + e);
            }
        }
        for (String e : ea) {
            if (!eb.contains(e)) {
                behavior.add("删除副作用: " + a.name() + " " + e);
            }
        }
    }

    private List<String> effectSignatures(RegisterDef reg) {
        return reg.sideEffects().stream()
                .map(e -> e.triggerField() + "->" + e.node() + ":" + e.action()
                        + (e.value() == null ? "" : "=" + e.value()))
                .sorted()
                .toList();
    }

    private Map<String, RegisterDef> map(ChipModel model) {
        Map<String, RegisterDef> map = new LinkedHashMap<>();
        model.allRegisters().forEach(r -> map.put(r.name(), r));
        return map;
    }

    private boolean eq(Object a, Object b) {
        return java.util.Objects.equals(a, b);
    }

    private String define(String chip, String reg, String suffix) {
        return (CGenerator.sanitize(chip) + "_"
                + CGenerator.sanitize(reg) + "_" + suffix).toUpperCase();
    }

    private String offsetDefine(String chip, String reg) {
        return define(chip, reg, "OFFSET");
    }

    private String accessor(String chip, String reg, String op) {
        return (CGenerator.sanitize(chip) + "_"
                + CGenerator.sanitize(reg) + "_" + op).toLowerCase();
    }

    private List<String> accessApis(String chip, String reg) {
        return List.of(accessor(chip, reg, "read"), accessor(chip, reg, "write"));
    }
}
