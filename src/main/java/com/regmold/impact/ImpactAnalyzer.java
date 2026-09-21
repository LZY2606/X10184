package com.regmold.impact;

import com.regmold.model.*;

import java.util.*;

/**
 * Diffs two model revisions and lists the generated API surface affected,
 * instead of silently renaming anything.
 */
public class ImpactAnalyzer {

    public List<ApiChange> diff(ChipModel a, ChipModel b) {
        List<ApiChange> out = new ArrayList<>();
        if (!a.endianness().equals(b.endianness())) {
            out.add(new ApiChange("BEHAVIOR", "ENDIANNESS_CHANGED", "REGMOLD_MODEL",
                    a.endianness() + " -> " + b.endianness() + ": all multi-word access sequences change"));
        }
        Map<String, RegisterDef> ra = byName(a), rb = byName(b);
        for (String name : ra.keySet()) {
            if (!rb.containsKey(name)) {
                for (String sym : symbolsOf(ra.get(name))) {
                    out.add(new ApiChange("ABI", "REMOVED", sym, "register " + name + " removed"));
                }
            }
        }
        for (String name : rb.keySet()) {
            if (!ra.containsKey(name)) {
                for (String sym : symbolsOf(rb.get(name))) {
                    out.add(new ApiChange("ABI", "ADDED", sym, "register " + name + " added"));
                }
            }
        }
        for (String name : ra.keySet()) {
            if (rb.containsKey(name)) diffRegister(ra.get(name), rb.get(name), out);
        }
        return out;
    }

    private void diffRegister(RegisterDef a, RegisterDef b, List<ApiChange> out) {
        String U = "REGMOLD_" + a.name().toUpperCase();
        String fn = "regmold_" + a.name().toLowerCase();
        if (a.address() != b.address()) {
            out.add(new ApiChange("ABI", "VALUE_CHANGED", U + "_ADDR",
                    hex(a.address()) + " -> " + hex(b.address())));
        }
        if (a.width() != b.width()) {
            out.add(new ApiChange("ABI", "WIDTH_CHANGED", fn + "_read/" + fn + "_write",
                    a.width() + " -> " + b.width() + " bits: value type and word sequence change"));
        }
        if (!a.wordOrder().equals(b.wordOrder())) {
            out.add(new ApiChange("BEHAVIOR", "WORD_ORDER_CHANGED", fn + "_read",
                    a.wordOrder() + " -> " + b.wordOrder()));
        }
        if (a.reset() != b.reset()) {
            out.add(new ApiChange("BEHAVIOR", "RESET_CHANGED", U + "_RESET", hex(a.reset()) + " -> " + hex(b.reset())));
        }
        if (!Objects.equals(a.semantic(), b.semantic())) {
            out.add(new ApiChange("BEHAVIOR", "ALIAS_SEMANTIC_CHANGED", fn + "_write",
                    a.semantic() + " -> " + b.semantic()));
        }
        if (!Objects.equals(a.lockedBy(), b.lockedBy())) {
            out.add(new ApiChange("BEHAVIOR", "LOCK_CHANGED", U + "_LOCK_REG",
                    String.valueOf(a.lockedBy()) + " -> " + b.lockedBy()));
        }
        Map<String, BitField> fa = fields(a), fb = fields(b);
        for (String f : fa.keySet()) {
            if (!fb.containsKey(f)) {
                out.add(new ApiChange("ABI", "REMOVED", U + "_" + f.toUpperCase() + "_MASK", "field removed"));
            }
        }
        for (String f : fb.keySet()) {
            if (!fa.containsKey(f)) {
                out.add(new ApiChange("ABI", "ADDED", U + "_" + f.toUpperCase() + "_MASK", "field added"));
            }
        }
        for (String f : fa.keySet()) {
            if (!fb.containsKey(f)) continue;
            BitField x = fa.get(f), y = fb.get(f);
            String sym = U + "_" + f.toUpperCase();
            if (x.mask() != y.mask()) {
                out.add(new ApiChange("ABI", "VALUE_CHANGED", sym + "_MASK",
                        hex(x.mask()) + " -> " + hex(y.mask())));
            }
            if (x.access() != y.access()) {
                out.add(new ApiChange("BEHAVIOR", "ACCESS_CHANGED", sym,
                        x.access() + " -> " + y.access()));
            }
            if (!Objects.equals(x.reset(), y.reset())) {
                out.add(new ApiChange("BEHAVIOR", "RESET_CHANGED", sym + "_RESET",
                        x.reset() + " -> " + y.reset()));
            }
            if (!x.sideEffects().equals(y.sideEffects())) {
                out.add(new ApiChange("BEHAVIOR", "SIDE_EFFECTS_CHANGED", sym,
                        x.sideEffects().size() + " -> " + y.sideEffects().size() + " effects"));
            }
        }
    }

    private List<String> symbolsOf(RegisterDef r) {
        List<String> syms = new ArrayList<>();
        String U = "REGMOLD_" + r.name().toUpperCase();
        syms.add(U + "_ADDR");
        syms.add(U + "_RESET");
        syms.add("regmold_" + r.name().toLowerCase() + "_read");
        syms.add("regmold_" + r.name().toLowerCase() + "_write");
        for (BitField f : r.fields()) syms.add(U + "_" + f.name().toUpperCase() + "_MASK");
        return syms;
    }

    private Map<String, RegisterDef> byName(ChipModel m) {
        Map<String, RegisterDef> map = new LinkedHashMap<>();
        for (RegisterDef r : m.registers()) map.put(r.name(), r);
        return map;
    }

    private Map<String, BitField> fields(RegisterDef r) {
        Map<String, BitField> map = new LinkedHashMap<>();
        for (BitField f : r.fields()) map.put(f.name(), f);
        return map;
    }

    private static String hex(long v) {
        return "0x" + Long.toHexString(v);
    }
}
