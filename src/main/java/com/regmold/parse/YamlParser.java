package com.regmold.parse;

import com.regmold.domain.Access;
import com.regmold.domain.EffectAction;
import com.regmold.domain.EffectModel;
import com.regmold.domain.FieldModel;
import com.regmold.domain.LockModel;
import com.regmold.domain.Model;
import com.regmold.domain.ParsedModel;
import com.regmold.domain.RegisterModel;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class YamlParser {

    @SuppressWarnings("unchecked")
    public ParsedModel parse(String yamlText) {
        ParsedModel parsed = new ParsedModel();
        Model model = new Model();
        parsed.model = model;
        Map<String, Object> root;
        try {
            Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
            Object doc = yaml.load(yamlText);
            if (doc == null) {
                throw new IllegalArgumentException("empty YAML document");
            }
            if (!(doc instanceof Map)) {
                throw new IllegalArgumentException("top-level YAML must be a mapping");
            }
            root = (Map<String, Object>) doc;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("YAML parse error: " + e.getMessage(), e);
        }

        model.name = str(root, "name", "unnamed");
        model.description = str(root, "description", "");
        model.dataWidth = (int) Bits.parseU64(root.getOrDefault("data_width", 32));
        model.endianness = str(root, "endianness", "little").toLowerCase();

        Object regs = root.get("registers");
        if (!(regs instanceof List<?> list) || list.isEmpty()) {
            throw new IllegalArgumentException("at least one register is required");
        }
        for (Object item : list) {
            model.registers.add(parseRegister((Map<String, Object>) item));
        }
        Object effects = root.get("effects");
        if (effects instanceof List<?> elist) {
            for (Object item : elist) {
                model.effects.add(parseEffect((Map<String, Object>) item));
            }
        }
        parsed.canonicalYaml = Canonicalizer.canonicalYaml(root);
        parsed.semanticHash = Canonicalizer.semanticHash(parsed.canonicalYaml);
        return parsed;
    }

    @SuppressWarnings("unchecked")
    private RegisterModel parseRegister(Map<String, Object> m) {
        RegisterModel r = new RegisterModel();
        r.name = require(m, "name");
        r.description = str(m, "description", "");
        r.offset = Bits.parseU64(require(m, "offset"));
        if (m.containsKey("width")) {
            r.width = (int) Bits.parseU64(m.get("width"));
        }
        if (m.containsKey("reset")) {
            r.reset = Bits.parseU64(m.get("reset"));
        }
        r.endianness = m.containsKey("endianness") ? str(m, "endianness", "little").toLowerCase() : null;
        r.aliasOf = m.containsKey("alias_of") ? m.get("alias_of").toString() : null;
        r.aliasWrite = m.containsKey("alias_write") ? m.get("alias_write").toString() : null;
        r.noClearRead = Boolean.TRUE.equals(m.get("no_clear_read"));

        if (m.get("lock") instanceof Map<?, ?> lm) {
            LockModel lock = new LockModel();
            lock.register = ((Map<String, Object>) lm).get("register").toString();
            lock.bit = (int) Bits.parseU64(((Map<String, Object>) lm).get("bit"));
            r.lock = lock;
        }

        Object fields = m.get("fields");
        if (fields instanceof List<?> fl) {
            for (Object f : fl) {
                r.fields.add(parseField((Map<String, Object>) f));
            }
        }
        return r;
    }

    private FieldModel parseField(Map<String, Object> m) {
        FieldModel f = new FieldModel();
        f.name = require(m, "name");
        f.description = str(m, "description", "");
        int[] range = Bits.range(require(m, "bits"));
        f.bitStart = range[0];
        f.bitCount = range[1];
        f.access = Access.valueOf(str(m, "access", "RW").toUpperCase());
        f.reset = Bits.parseU64(m.getOrDefault("reset", 0L));
        f.latchFrom = m.containsKey("latch_from") ? m.get("latch_from").toString() : null;
        return f;
    }

    @SuppressWarnings("unchecked")
    private EffectModel parseEffect(Map<String, Object> m) {
        EffectModel e = new EffectModel();
        e.name = require(m, "name");
        e.description = str(m, "description", "");
        e.triggerRegister = require(m, "trigger");
        if (m.containsKey("when_bits")) {
            e.triggerMask = parseBitList(m.get("when_bits"));
        } else if (m.containsKey("mask")) {
            e.triggerMask = Bits.parseU64(m.get("mask"));
        }
        String action = str(m, "action", "set").toUpperCase();
        e.action = EffectAction.valueOf(action);
        Map<String, Object> target = (Map<String, Object>) m.get("target");
        if (target == null) { throw new IllegalArgumentException("effect " + e.name + " missing target"); }
        e.targetRegister = target.get("register").toString();
        e.targetField = target.containsKey("field") ? target.get("field").toString() : null;
        if (m.get("source") instanceof Map<?, ?> source) {
            Map<String, Object> sm = (Map<String, Object>) source;
            e.sourceRegister = sm.get("register").toString();
            e.sourceField = sm.containsKey("field") ? sm.get("field").toString() : null;
        }
        return e;
    }

    @SuppressWarnings("unchecked")
    private long parseBitList(Object raw) {
        long mask = 0L;
        for (Object o : (List<Object>) raw) {
            int[] r = Bits.range(o.toString());
            mask |= Bits.mask(r[0], r[1]);
        }
        return mask;
    }

    private String require(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null) {
            throw new IllegalArgumentException("missing key: " + key);
        }
        return v.toString();
    }

    private String str(Map<String, Object> m, String key, String dflt) {
        Object v = m.get(key);
        return v == null ? dflt : v.toString();
    }
}
