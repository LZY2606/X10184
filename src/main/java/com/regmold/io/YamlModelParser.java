package com.regmold.io;

import com.regmold.domain.Access;
import com.regmold.domain.Effect;
import com.regmold.domain.EffectAction;
import com.regmold.domain.Endianness;
import com.regmold.domain.Field;
import com.regmold.domain.LockRule;
import com.regmold.domain.Model;
import com.regmold.domain.ReadOrder;
import com.regmold.domain.Ref;
import com.regmold.domain.Register;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Parses register-map YAML. Field and register order in the source is preserved here;
 *  semantic canonicalization happens later so key order never affects output. */
public final class YamlModelParser {

    private YamlModelParser() {
    }

    public static Model parse(String yaml) {
        List<String> errors = new ArrayList<>();
        Object root;
        try {
            root = new Yaml(new SafeConstructor(new LoaderOptions())).load(yaml);
        } catch (RuntimeException e) {
            throw new YamlParseException("YAML syntax error: " + e.getMessage());
        }
        if (root == null) {
            throw new YamlParseException("empty document");
        }
        if (!(root instanceof Map<?, ?> rootMap)) {
            throw new YamlParseException("top level must be a mapping");
        }

        String name = str(rootMap, "name", errors, true);
        String version = str(rootMap, "version", errors, true);
        int addressBits = intVal(rootMap, "address_bits", 32, errors);
        int wordBytes = intVal(rootMap, "word_bytes", 4, errors);
        Endianness endianness;
        try {
            endianness = Endianness.fromKey(str(rootMap, "endianness", "le"));
        } catch (IllegalArgumentException e) {
            endianness = Endianness.LE;
            errors.add("endianness must be 'le' or 'be'");
        }

        List<Register> registers = new ArrayList<>();
        Object regsObj = rootMap.get("registers");
        if (regsObj instanceof List<?> list) {
            for (Object item : list) {
                parseRegisterEntry(item, wordBytes, registers, errors);
            }
        } else if (regsObj instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                parseRegisterEntry(e.getKey(), e.getValue(), wordBytes, registers, errors);
            }
        } else if (regsObj != null) {
            errors.add("'registers' must be a list or mapping");
        }

        List<Effect> effects = parseEffects(rootMap.get("effects"), errors);
        List<LockRule> locks = parseLocks(rootMap.get("locks"), errors);

        if (!errors.isEmpty()) {
            throw new YamlParseException(errors);
        }
        return new Model(name, version, addressBits, wordBytes, endianness, registers, effects, locks,
                yaml, 0, null);
    }

    private static void parseRegisterEntry(Object item, int defaultWidth,
                                           List<Register> out, List<String> errors) {
        if (item instanceof Map<?, ?> map) {
            parseRegister(map.get("name"), map, defaultWidth, out, errors);
        } else {
            errors.add("register entry must be a mapping");
        }
    }

    private static void parseRegisterEntry(Object key, Object value, int defaultWidth,
                                           List<Register> out, List<String> errors) {
        if (!(value instanceof Map<?, ?> map)) {
            errors.add("register '" + key + "' must be a mapping");
            return;
        }
        parseRegister(key, map, defaultWidth, out, errors);
    }

    private static void parseRegister(Object keyObj, Map<?, ?> map, int defaultWidth,
                                      List<Register> out, List<String> errors) {
        String rname = keyObj != null ? String.valueOf(keyObj).trim() : null;
        String innerName = str(map, "name", null, errors, false);
        if (innerName != null) {
            rname = innerName;
        }
        String ctx = rname != null ? "register '" + rname + "'" : "register";
        if (rname == null || rname.isBlank()) {
            errors.add("register without a name");
            return;
        }
        Long address = parseLong(map.get("address"), ctx + ": address", errors);
        int widthBits = intVal(map, "width_bits", intVal(map, "width", defaultWidth * 8, errors), errors);
        Access access;
        try {
            access = Access.fromKey(str(map, "access", "rw"));
        } catch (IllegalArgumentException e) {
            errors.add(ctx + ": " + e.getMessage());
            access = Access.RW;
        }
        Long reset = null;
        if (map.containsKey("reset")) {
            reset = parseLong(map.get("reset"), ctx + ": reset", errors);
        }
        String aliasOf = str(map, "alias_of", null, errors, false);
        ReadOrder readOrder = null;
        if (map.containsKey("read_order")) {
            try {
                readOrder = ReadOrder.fromKey(str(map, "read_order", "low-first"));
            } catch (IllegalArgumentException e) {
                errors.add(ctx + ": " + e.getMessage());
            }
        }
        String description = str(map, "description", "", errors, false);

        List<Field> fields = new ArrayList<>();
        Object fieldsObj = map.get("fields");
        if (fieldsObj instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> fm) {
                    fields.add(parseField(fm, errors, ctx));
                } else {
                    errors.add(ctx + ": field entry must be a mapping");
                }
            }
        } else if (fieldsObj instanceof Map<?, ?> fmap) {
            for (Map.Entry<?, ?> e : fmap.entrySet()) {
                if (e.getValue() instanceof Map<?, ?> fm) {
                    Map<String, Object> merged = new LinkedHashMap<>();
                    merged.put("name", e.getKey());
                    merged.putAll((Map<String, Object>) fm);
                    fields.add(parseField(merged, errors, ctx));
                } else {
                    errors.add(ctx + ": field '" + e.getKey() + "' must be a mapping");
                }
            }
        } else if (fieldsObj != null) {
            errors.add(ctx + ": 'fields' must be a list or mapping");
        }

        out.add(new Register(rname, address == null ? 0 : address, widthBits, access, reset,
                List.copyOf(fields), aliasOf, readOrder, description));
    }

    private static Field parseField(Map<?, ?> map, List<String> errors, String ctx) {
        String name = str(map, "name", null, errors, false);
        if (name == null || name.isBlank()) {
            errors.add(ctx + ": field without a name");
            name = "?";
        }
        int lsb;
        int width;
        Object bits = map.get("bits");
        if (bits instanceof List<?> range && range.size() == 2) {
            Integer hi = parseInt(range.get(0));
            Integer lo = parseInt(range.get(1));
            if (hi == null || lo == null) {
                errors.add(ctx + " field '" + name + "': bits must be integers");
                lsb = 0;
                width = 1;
            } else {
                lsb = Math.min(hi, lo);
                width = Math.abs(hi - lo) + 1;
            }
        } else if (bits != null) {
            Integer b = parseInt(bits);
            if (b == null) {
                errors.add(ctx + " field '" + name + "': bits must be integer or [msb, lsb]");
                lsb = 0;
                width = 1;
            } else {
                lsb = b;
                width = 1;
            }
        } else {
            lsb = intVal(map, "lsb", -1, errors);
            width = intVal(map, "width", 1, errors);
            if (lsb < 0) {
                errors.add(ctx + " field '" + name + "': missing lsb/bits");
                lsb = 0;
            }
        }
        Access access;
        try {
            access = Access.fromKey(str(map, "access", "rw"));
        } catch (IllegalArgumentException e) {
            errors.add(ctx + " field '" + name + "': " + e.getMessage());
            access = Access.RW;
        }
        long reset = map.containsKey("reset") ? numOrZero(map.get("reset"), errors) : 0L;
        String description = str(map, "description", "", errors, false);
        return new Field(name, lsb, width, access, reset, description);
    }

    private static List<Effect> parseEffects(Object obj, List<String> errors) {
        List<Effect> out = new ArrayList<>();
        if (obj == null) {
            return out;
        }
        if (!(obj instanceof List<?> list)) {
            errors.add("'effects' must be a list");
            return out;
        }
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) {
                errors.add("effect entry must be a mapping");
                continue;
            }
            String trigger = firstStr(m, "trigger", "on", "when");
            String action = firstStr(m, "action", "do");
            String target = firstStr(m, "target");
            if (trigger == null || action == null || target == null) {
                errors.add("effect requires trigger, action and target: " + m);
                continue;
            }
            try {
                out.add(new Effect(Ref.parse(trigger), EffectAction.fromKey(action),
                        Ref.parse(target), str(m, "description", "", errors, false)));
            } catch (IllegalArgumentException e) {
                errors.add("effect: " + e.getMessage());
            }
        }
        return out;
    }

    private static List<LockRule> parseLocks(Object obj, List<String> errors) {
        List<LockRule> out = new ArrayList<>();
        if (obj == null) {
            return out;
        }
        if (!(obj instanceof List<?> list)) {
            errors.add("'locks' must be a list");
            return out;
        }
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) {
                errors.add("lock entry must be a mapping");
                continue;
            }
            String target = firstStr(m, "target", "lock");
            String master = firstStr(m, "master", "when");
            if (target == null || master == null) {
                errors.add("lock requires target and master: " + m);
                continue;
            }
            out.add(new LockRule(Ref.parse(target), Ref.parse(master)));
        }
        return out;
    }

    private static String firstStr(Map<?, ?> m, String... keys) {
        for (String k : keys) {
            Object v = m.get(k);
            if (v != null) {
                return String.valueOf(v).trim();
            }
        }
        return null;
    }

    private static String str(Map<?, ?> m, String key, List<String> errors, boolean required) {
        Object v = m.get(key);
        if (v == null) {
            if (required) {
                errors.add("missing required key '" + key + "'");
            }
            return "";
        }
        return String.valueOf(v).trim();
    }

    private static String str(Map<?, ?> m, String key, String def) {
        Object v = m.get(key);
        return v == null ? def : String.valueOf(v).trim();
    }

    private static String str(Map<?, ?> m, String key, String def, List<String> errors, boolean required) {
        return str(m, key, def);
    }

    private static int intVal(Map<?, ?> m, String key, int def, List<String> errors) {
        Object v = m.get(key);
        if (v == null) {
            return def;
        }
        Integer i = parseInt(v);
        if (i == null) {
            errors.add("'" + key + "' must be an integer");
            return def;
        }
        return i;
    }

    private static Integer parseInt(Object v) {
        Long l = parseLong(v, null, null);
        return l == null ? null : (int) (long) l;
    }

    private static long numOrZero(Object v, List<String> errors) {
        Long l = parseLong(v, null, null);
        if (l == null && errors != null) {
            errors.add("expected numeric value, got: " + v);
        }
        return l == null ? 0 : l;
    }

    private static Long parseLong(Object v, String what, List<String> errors) {
        if (v == null) {
            if (errors != null) {
                errors.add("missing required value" + (what != null ? " for " + what : ""));
            }
            return 0L;
        }
        String s = String.valueOf(v).trim().replace("_", "");
        try {
            if (s.startsWith("0x") || s.startsWith("0X")) {
                return Long.parseUnsignedLong(s.substring(2), 16);
            }
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            if (errors != null) {
                errors.add((what != null ? what + ": " : "") + "not a number: " + v);
            }
            return 0L;
        }
    }
}
