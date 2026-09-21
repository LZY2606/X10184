package com.regmold.gen;

import com.regmold.domain.Field;
import com.regmold.domain.Model;
import com.regmold.domain.Register;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The deterministic list of public symbols a model generates, in both languages. */
public final class SymbolInventory {

    private SymbolInventory() {
    }

    public static List<SymbolInfo> symbols(Model model) {
        String pfx = Naming.snake(model.name());
        Map<String, SymbolInfo> out = new LinkedHashMap<>();
        List<String> both = List.of("c", "rust");
        for (Register r : model.registers()) {
            String regSym = pfx + "_" + Naming.snake(r.name());
            out.put(regSym, new SymbolInfo(regSym, "register", r.name(), null,
                    r.address(), r.widthBits(), r.access().key(), both));
            for (String op : new String[]{"read", "write", "set", "clear"}) {
                String s = regSym + "_" + op;
                out.put(s, new SymbolInfo(s, "api_" + op, r.name(), null,
                        r.address(), r.widthBits(), r.access().key(), both));
            }
            for (Field f : r.fields()) {
                String base = regSym + "_" + Naming.snake(f.name());
                for (String suffix : new String[]{"shift", "mask", "reset"}) {
                    String s = base + "_" + suffix;
                    out.put(s, new SymbolInfo(s, "field_" + suffix, r.name(), f.name(),
                            r.address(), f.width(), f.access().key(), both));
                }
            }
        }
        return List.copyOf(out.values());
    }
}
