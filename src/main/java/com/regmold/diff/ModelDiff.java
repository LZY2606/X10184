package com.regmold.diff;

import com.regmold.domain.Access;
import com.regmold.domain.Effect;
import com.regmold.domain.Field;
import com.regmold.domain.LockRule;
import com.regmold.domain.Model;
import com.regmold.domain.Register;
import com.regmold.gen.Naming;
import com.regmold.gen.SymbolInfo;
import com.regmold.gen.SymbolInventory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Compares two model revisions: ABI-level changes and behavior changes, with the exact
 *  generated API symbols impacted (never silent renames). */
public final class ModelDiff {

    private ModelDiff() {
    }

    public record Report(List<DiffEntry> entries, List<String> addedApis,
                         List<String> removedApis, List<String> impactedApis) {
    }

    public static Report compare(Model oldModel, Model newModel) {
        List<DiffEntry> entries = new ArrayList<>();
        Map<String, List<SymbolInfo>> oldSymbols = byReg(SymbolInventory.symbols(oldModel));
        Map<String, List<SymbolInfo>> newSymbols = byReg(SymbolInventory.symbols(newModel));

        Map<String, Register> oldRegs = new LinkedHashMap<>();
        Map<String, Register> newRegs = new LinkedHashMap<>();
        for (Register r : oldModel.registers()) {
            oldRegs.put(r.name(), r);
        }
        for (Register r : newModel.registers()) {
            newRegs.put(r.name(), r);
        }

        for (Register nr : newModel.registers()) {
            if (!oldRegs.containsKey(nr.name())) {
                entries.add(new DiffEntry("abi", "addition",
                        "register '" + nr.name() + "' added", nr.name(), null,
                        syms(newSymbols, nr.name())));
            }
        }
        for (Register or : oldModel.registers()) {
            if (!newRegs.containsKey(or.name())) {
                entries.add(new DiffEntry("abi", "breaking",
                        "register '" + or.name() + "' removed (generated symbols deleted)",
                        or.name(), null, syms(oldSymbols, or.name())));
            }
        }

        for (Register nr : newModel.registers()) {
            Register or = oldRegs.get(nr.name());
            if (or == null) {
                continue;
            }
            compareRegister(or, nr, entries, oldSymbols, newSymbols);
        }

        if (oldModel.endianness() != newModel.endianness()) {
            entries.add(new DiffEntry("abi", "breaking",
                    "endianness changed " + oldModel.endianness() + " -> " + newModel.endianness()
                            + "; byte ordering of every multi-word access changes",
                    null, null, allApis(newModel)));
        }
        if (oldModel.wordBytes() != newModel.wordBytes()) {
            entries.add(new DiffEntry("abi", "breaking",
                    "bus word width changed " + oldModel.wordBytes() + " -> " + newModel.wordBytes()
                            + " bytes; word access signatures change",
                    null, null, allApis(newModel)));
        }
        if (oldModel.addressBits() != newModel.addressBits()) {
            entries.add(new DiffEntry("behavior", "info",
                    "address space width changed " + oldModel.addressBits() + " -> "
                            + newModel.addressBits() + " bits",
                    null, null, allApis(newModel)));
        }

        compareEffects(oldModel, newModel, entries);
        compareLocks(oldModel, newModel, entries);

        return summarize(entries, oldModel, newModel);
    }

    private static void compareRegister(Register or, Register nr, List<DiffEntry> entries,
                                        Map<String, List<SymbolInfo>> oldSymbols,
                                        Map<String, List<SymbolInfo>> newSymbols) {
        List<String> regApis = syms(newSymbols, nr.name());
        if (or.address() != nr.address()) {
            entries.add(new DiffEntry("abi", "breaking",
                    "address of '" + nr.name() + "' changed 0x" + Long.toUnsignedString(or.address(), 16)
                            + " -> 0x" + Long.toUnsignedString(nr.address(), 16),
                    nr.name(), null, regApis));
        }
        if (or.widthBits() != nr.widthBits()) {
            entries.add(new DiffEntry("abi", "breaking",
                    "width of '" + nr.name() + "' changed " + or.widthBits() + " -> " + nr.widthBits()
                            + " bits; read/write value type changes",
                    nr.name(), null, regApis));
        }
        if (or.access() != nr.access()) {
            entries.add(new DiffEntry("behavior", "breaking",
                    "register access of '" + nr.name() + "' changed " + or.access().key()
                            + " -> " + nr.access().key(),
                    nr.name(), null, regApis));
        }
        if (!eq(or.readOrder(), nr.readOrder())) {
            entries.add(new DiffEntry("behavior", "breaking",
                    "read order of '" + nr.name() + "' changed "
                            + (or.readOrder() == null ? "-" : or.readOrder().key()) + " -> "
                            + (nr.readOrder() == null ? "-" : nr.readOrder().key())
                            + "; high/low-word latch sequence changes",
                    nr.name(), null, regApis));
        }

        Map<String, Field> of = new HashMap<>();
        Map<String, Field> nf = new HashMap<>();
        for (Field f : or.fields()) {
            of.put(f.name(), f);
        }
        for (Field f : nr.fields()) {
            nf.put(f.name(), f);
        }
        for (Field f : nr.fields()) {
            Field old = of.get(f.name());
            if (old == null) {
                entries.add(new DiffEntry("abi", "addition",
                        "field '" + nr.name() + "." + f.name() + "' added", nr.name(), f.name(),
                        fieldApis(nr.name(), f.name(), newSymbols)));
                continue;
            }
            if (old.lsb() != f.lsb() || old.width() != f.width()) {
                entries.add(new DiffEntry("abi", "breaking",
                        "field '" + nr.name() + "." + f.name() + "' geometry changed ["
                                + old.msb() + ":" + old.lsb() + " w" + old.width() + "] -> ["
                                + f.msb() + ":" + f.lsb() + " w" + f.width() + "]; masks/shifts change",
                        nr.name(), f.name(), fieldApis(nr.name(), f.name(), newSymbols)));
            }
            if (old.access() != f.access()) {
                entries.add(new DiffEntry("behavior", "breaking",
                        "field '" + nr.name() + "." + f.name() + "' access changed "
                                + old.access().key() + " -> " + f.access().key(),
                        nr.name(), f.name(), fieldApis(nr.name(), f.name(), newSymbols)));
            }
            if (old.reset() != f.reset()) {
                entries.add(new DiffEntry("behavior", "info",
                        "field '" + nr.name() + "." + f.name() + "' reset changed 0x"
                                + Long.toUnsignedString(old.reset(), 16) + " -> 0x"
                                + Long.toUnsignedString(f.reset(), 16),
                        nr.name(), f.name(), fieldApis(nr.name(), f.name(), newSymbols)));
            }
        }
        for (Field f : or.fields()) {
            if (!nf.containsKey(f.name())) {
                entries.add(new DiffEntry("abi", "breaking",
                        "field '" + nr.name() + "." + f.name() + "' removed; its symbols are deleted",
                        nr.name(), f.name(), fieldApis(nr.name(), f.name(), oldSymbols)));
            }
        }
    }

    private static void compareEffects(Model a, Model b, List<DiffEntry> entries) {
        Set<String> ea = new HashSet<>();
        Set<String> eb = new HashSet<>();
        for (Effect e : a.effects()) {
            ea.add(key(e));
        }
        for (Effect e : b.effects()) {
            eb.add(key(e));
        }
        for (Effect e : b.effects()) {
            if (!ea.contains(key(e))) {
                entries.add(new DiffEntry("behavior", "info",
                        "side effect added: write " + e.trigger().canonical() + " "
                                + e.action().name().toLowerCase() + "s " + e.target().canonical(),
                        e.trigger().register(), null, List.of()));
            }
        }
        for (Effect e : a.effects()) {
            if (!eb.contains(key(e))) {
                entries.add(new DiffEntry("behavior", "breaking",
                        "side effect removed: write " + e.trigger().canonical() + " "
                                + e.action().name().toLowerCase() + "s " + e.target().canonical(),
                        e.trigger().register(), null, List.of()));
            }
        }
    }

    private static void compareLocks(Model a, Model b, List<DiffEntry> entries) {
        Set<String> la = new HashSet<>();
        Set<String> lb = new HashSet<>();
        for (LockRule l : a.locks()) {
            la.add(l.target().canonical() + "<-" + l.master().canonical());
        }
        for (LockRule l : b.locks()) {
            lb.add(l.target().canonical() + "<-" + l.master().canonical());
        }
        for (LockRule l : b.locks()) {
            if (!la.contains(l.target().canonical() + "<-" + l.master().canonical())) {
                entries.add(new DiffEntry("behavior", "info",
                        "lock added: '" + l.target().canonical() + "' writable only while '"
                                + l.master().canonical() + "' is clear",
                        l.target().register(), null, List.of()));
            }
        }
    }

    private static String key(Effect e) {
        return e.trigger().canonical() + "|" + e.action() + "|" + e.target().canonical();
    }

    private static boolean eq(Object a, Object b) {
        return java.util.Objects.equals(a, b);
    }

    private static Map<String, List<SymbolInfo>> byReg(List<SymbolInfo> all) {
        Map<String, List<SymbolInfo>> m = new LinkedHashMap<>();
        for (SymbolInfo s : all) {
            m.computeIfAbsent(s.register(), k -> new ArrayList<>()).add(s);
        }
        return m;
    }

    private static List<String> syms(Map<String, List<SymbolInfo>> map, String reg) {
        List<String> out = new ArrayList<>();
        for (SymbolInfo s : map.getOrDefault(reg, List.of())) {
            out.add(s.symbol());
        }
        return out;
    }

    private static List<String> fieldApis(String reg, String field,
                                          Map<String, List<SymbolInfo>> map) {
        String needle = "_" + Naming.snake(field) + "_";
        List<String> out = new ArrayList<>();
        for (SymbolInfo s : map.getOrDefault(reg, List.of())) {
            if (s.field() != null && s.field().equals(field)) {
                out.add(s.symbol());
            }
        }
        if (out.isEmpty()) {
            out.addAll(syms(map, reg));
        }
        return out;
    }

    private static List<String> allApis(Model m) {
        List<String> out = new ArrayList<>();
        for (SymbolInfo s : SymbolInventory.symbols(m)) {
            out.add(s.symbol());
        }
        return out;
    }

    private static Report summarize(List<DiffEntry> entries, Model oldModel, Model newModel) {
        Set<String> added = new HashSet<>();
        Set<String> removed = new HashSet<>();
        Set<String> impacted = new HashSet<>();
        for (DiffEntry e : entries) {
            for (String api : e.affectedApis()) {
                impacted.add(api);
                if ("addition".equals(e.severity())) {
                    added.add(api);
                } else if ("breaking".equals(e.severity()) && "abi".equals(e.kind())
                        && e.message().contains("removed")) {
                    removed.add(api);
                }
            }
        }
        return new Report(List.copyOf(entries), sort(added), sort(removed), sort(impacted));
    }

    private static List<String> sort(Set<String> s) {
        List<String> l = new ArrayList<>(s);
        java.util.Collections.sort(l);
        return l;
    }
}
