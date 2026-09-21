package com.regmold.model;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Canonical, sorted register model. Codegen and fingerprinting use only this form. */
public record ChipModel(String name, String endianness, List<RegisterDef> registers) {

    public ChipModel {
        if (endianness == null || endianness.isBlank()) endianness = "little";
        List<RegisterDef> sorted = new ArrayList<>(registers);
        sorted.sort(Comparator.comparingLong(RegisterDef::address).thenComparing(RegisterDef::name));
        registers = List.copyOf(sorted);
    }

    public RegisterDef register(String name) {
        for (RegisterDef r : registers) if (r.name().equals(name)) return r;
        return null;
    }

    /** Resolve an alias to its base register, following chains. */
    public RegisterDef resolveBase(RegisterDef r) {
        RegisterDef cur = r;
        while (cur.isAlias()) {
            RegisterDef next = register(cur.aliasOf());
            if (next == null) return cur;
            cur = next;
        }
        return cur;
    }
}
