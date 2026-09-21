package com.regmold.diff;

import com.regmold.domain.FieldModel;
import com.regmold.domain.Model;
import com.regmold.domain.RegisterModel;
import com.regmold.validate.ModelIndex;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ModelDiffer {

    public List<DiffEntry> diff(Model oldModel, Model newModel) {
        ModelIndex a = new ModelIndex(oldModel);
        ModelIndex b = new ModelIndex(newModel);
        List<DiffEntry> entries = new ArrayList<>();

        if (oldModel.dataWidth != newModel.dataWidth) {
            add(entries, "ABI", "data_width",
                    "bus width changed", String.valueOf(oldModel.dataWidth), String.valueOf(newModel.dataWidth),
                    allApis(newModel));
        }
        if (!oldModel.endianness.equals(newModel.endianness)) {
            add(entries, "BEHAVIOR", "endianness",
                    "endianness changed; multi-word read/write word order flips",
                    oldModel.endianness, newModel.endianness, allApis(newModel));
        }

        Set<String> names = new HashSet<>();
        names.addAll(a.byName.keySet());
        names.addAll(b.byName.keySet());
        for (String name : names.stream().sorted().toList()) {
            RegisterModel ra = a.byName.get(name);
            RegisterModel rb = b.byName.get(name);
            if (ra == null) {
                add(entries, "ABI", "register", "register added", null, null, apisFor(name));
                continue;
            }
            if (rb == null) {
                add(entries, "ABI", "register", "register removed (no silent rename)", null, null, apisFor(name));
                continue;
            }
            compareRegister(entries, name, ra, rb, a, b);
        }
        compareEffects(entries, oldModel, newModel);
        return entries;
    }

    private void compareRegister(List<DiffEntry> entries, String name, RegisterModel ra, RegisterModel rb,
                                 ModelIndex a, ModelIndex b) {
        if (ra.offset != rb.offset) {
            add(entries, "ABI", "register", "address changed for " + name,
                    "0x" + Long.toUnsignedString(ra.offset, 16),
                    "0x" + Long.toUnsignedString(rb.offset, 16), apisFor(name));
        }
        int wa = a.width(ra);
        int wb = b.width(rb);
        if (wa != wb) {
            add(entries, "ABI", "register", "width changed for " + name,
                    wa + " bits", wb + " bits", apisFor(name));
        }
        long resetA = resetOf(ra);
        long resetB = resetOf(rb);
        if (resetA != resetB) {
            add(entries, "BEHAVIOR", "register", "reset value changed for " + name,
                    "0x" + Long.toUnsignedString(resetA, 16), "0x" + Long.toUnsignedString(resetB, 16),
                    apisFor(name));
        }
        if (!str(ra.endianness, a.model.endianness).equals(str(rb.endianness, b.model.endianness))) {
            add(entries, "BEHAVIOR", "register", "register endianness changed for " + name,
                    str(ra.endianness, a.model.endianness), str(rb.endianness, b.model.endianness), apisFor(name));
        }
        if (!str(ra.aliasWrite, "normal").equals(str(rb.aliasWrite, "normal"))
                || !str(ra.aliasOf, "-").equals(str(rb.aliasOf, "-"))) {
            add(entries, "BEHAVIOR", "register", "alias write semantics changed for " + name,
                    str(ra.aliasOf, "-") + "/" + str(ra.aliasWrite, "normal"),
                    str(rb.aliasOf, "-") + "/" + str(rb.aliasWrite, "normal"), apisFor(name));
        }
        if (differsLock(ra, rb)) {
            add(entries, "BEHAVIOR", "register", "lock relation changed for " + name,
                    lockText(ra), lockText(rb), apisFor(name));
        }

        Set<String> fields = new HashSet<>();
        for (FieldModel f : ra.fields) {
            fields.add(f.name);
        }
        for (FieldModel f : rb.fields) {
            fields.add(f.name);
        }
        for (String fn : fields.stream().sorted().toList()) {
            FieldModel fa = ra.fields.stream().filter(f -> f.name.equals(fn)).findFirst().orElse(null);
            FieldModel fb = rb.fields.stream().filter(f -> f.name.equals(fn)).findFirst().orElse(null);
            if (fa == null) {
                add(entries, "ABI", "field", "field " + name + "." + fn + " added", null, null, fieldApis(name, fn));
                continue;
            }
            if (fb == null) {
                add(entries, "ABI", "field", "field " + name + "." + fn + " removed (no silent rename)",
                        null, null, fieldApis(name, fn));
                continue;
            }
            if (fa.bitStart != fb.bitStart || fa.bitCount != fb.bitCount) {
                add(entries, "ABI", "field", "bits of " + name + "." + fn + " changed",
                        "[" + fa.bitEnd() + ":" + fa.bitStart + "]",
                        "[" + fb.bitEnd() + ":" + fb.bitStart + "]", fieldApis(name, fn));
            }
            if (fa.access != fb.access) {
                add(entries, "BEHAVIOR", "field", "access of " + name + "." + fn + " changed",
                        fa.access.name(), fb.access.name(), fieldApis(name, fn));
            }
            if (fa.reset != fb.reset) {
                add(entries, "BEHAVIOR", "field", "reset of " + name + "." + fn + " changed",
                        "0x" + Long.toUnsignedString(fa.reset, 16),
                        "0x" + Long.toUnsignedString(fb.reset, 16), fieldApis(name, fn));
            }
            if (!str(fa.latchFrom, "-").equals(str(fb.latchFrom, "-"))) {
                add(entries, "BEHAVIOR", "field", "latch relation of " + name + "." + fn + " changed",
                        str(fa.latchFrom, "-"), str(fb.latchFrom, "-"), fieldApis(name, fn));
            }
        }
    }

    private void compareEffects(List<DiffEntry> entries, Model oldModel, Model newModel) {
        Set<String> names = new HashSet<>();
        oldModel.effects.forEach(e -> names.add(e.name));
        newModel.effects.forEach(e -> names.add(e.name));
        for (String name : names.stream().sorted().toList()) {
            var ea = oldModel.effects.stream().filter(e -> e.name.equals(name)).findFirst().orElse(null);
            var eb = newModel.effects.stream().filter(e -> e.name.equals(name)).findFirst().orElse(null);
            if (ea == null) {
                add(entries, "BEHAVIOR", "effect", "effect added: " + name, null, null, List.of());
            } else if (eb == null) {
                add(entries, "BEHAVIOR", "effect", "effect removed: " + name, null, null, List.of());
            } else if (!signature(ea).equals(signature(eb))) {
                add(entries, "BEHAVIOR", "effect", "effect changed: " + name, signature(ea), signature(eb), List.of());
            }
        }
    }

    private String signature(com.regmold.domain.EffectModel e) {
        return e.triggerRegister + ":0x" + Long.toUnsignedString(e.triggerMask, 16) + "->"
                + e.action + ":" + e.targetRegister + "." + (e.targetField == null ? "*" : e.targetField)
                + "<-" + (e.sourceRegister == null ? "-" : e.sourceRegister + "." + (e.sourceField == null ? "*" : e.sourceField));
    }

    private boolean differsLock(RegisterModel a, RegisterModel b) {
        return !lockText(a).equals(lockText(b));
    }

    private String lockText(RegisterModel r) {
        if (r.lock == null) {
            return "-";
        }
        return r.lock.register + "[" + r.lock.bit + "]";
    }

    private long resetOf(RegisterModel r) {
        if (r.reset != null) {
            return r.reset;
        }
        long v = 0L;
        for (FieldModel f : r.fields) {
            v |= f.reset & f.linearMask();
        }
        return v;
    }

    private String str(String s, String dflt) {
        return s == null ? dflt : s;
    }

    private void add(List<DiffEntry> entries, String kind, String scope, String detail,
                     String from, String to, List<String> affected) {
        DiffEntry e = new DiffEntry();
        e.kind = kind;
        e.scope = scope;
        e.detail = detail;
        e.from = from;
        e.to = to;
        e.affectedApis = new ArrayList<>(affected);
        entries.add(e);
    }

    private List<String> apisFor(String reg) {
        String s = snake(reg);
        return List.of("C: " + s + "_read()", "C: " + s + "_peek()", "C: " + s + "_write()",
                "Rust: " + s + "::read", "Rust: " + s + "::peek", "Rust: " + s + "::write");
    }

    private List<String> fieldApis(String reg, String field) {
        String s = snake(reg) + "_" + snake(field);
        return List.of("C: " + s + "_get()", "C: " + s + "_set()",
                "Rust: " + snake(reg) + "::" + snake(field) + "::get",
                "Rust: " + snake(reg) + "::" + snake(field) + "::set");
    }

    private List<String> allApis(Model model) {
        List<String> out = new ArrayList<>();
        for (RegisterModel r : model.registers) {
            out.addAll(apisFor(r.name));
        }
        return out;
    }

    private String snake(String name) {
        return com.regmold.gen.Names.cIdent(name);
    }
}
