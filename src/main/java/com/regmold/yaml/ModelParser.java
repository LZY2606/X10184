package com.regmold.yaml;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.regmold.model.*;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** Parses register-mold YAML into a canonical ChipModel. */
public class ModelParser {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    public static ChipModel parse(String yamlText) {
        try {
            JsonNode root = YAML.readTree(yamlText);
            String name = text(root, "name", "chip");
            String endianness = text(root, "endianness", "little");
            List<RegisterDef> regs = new ArrayList<>();
            JsonNode regsNode = root.get("registers");
            if (regsNode == null || !regsNode.isArray()) {
                throw new IllegalArgumentException("YAML must contain a 'registers' list");
            }
            for (JsonNode rn : regsNode) {
                regs.add(parseRegister(rn));
            }
            return new ChipModel(name, endianness, regs);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid YAML: " + e.getMessage(), e);
        }
    }

    private static RegisterDef parseRegister(JsonNode rn) {
        String name = requiredText(rn, "name");
        long address = parseNumber(rn.get("address"), "address of " + name);
        int width = (int) parseNumber(rn.get("width"), "width of " + name, 32);
        long reset = parseNumber(rn.get("reset"), "reset of " + name, 0);
        String wordOrder = text(rn, "wordOrder", "lo-first");
        String aliasOf = text(rn, "aliasOf", null);
        String semantic = text(rn, "semantic", null);
        String doc = text(rn, "doc", "");
        LockSpec lock = null;
        JsonNode ln = rn.get("lockedBy");
        if (ln != null) {
            lock = new LockSpec(requiredText(ln, "register"), requiredText(ln, "field"),
                    parseNumber(ln.get("openValue"), "openValue", 1));
        }
        List<BitField> fields = new ArrayList<>();
        JsonNode fn = rn.get("fields");
        if (fn != null && fn.isArray()) {
            for (JsonNode f : fn) {
                fields.add(parseField(f));
            }
        }
        fields.sort(java.util.Comparator.comparingInt(BitField::offset).thenComparing(BitField::name));
        return new RegisterDef(name, address, width, reset, wordOrder, fields, aliasOf, semantic, lock, doc);
    }

    private static BitField parseField(JsonNode f) {
        String name = requiredText(f, "name");
        int offset = (int) parseNumber(f.get("offset"), "offset of " + name);
        int width = (int) parseNumber(f.get("width"), "width of " + name, 1);
        AccessType access = AccessType.fromString(text(f, "access", "rw"));
        Long reset = f.has("reset") ? parseNumber(f.get("reset"), "reset of " + name) : null;
        String doc = text(f, "doc", "");
        List<SideEffect> effects = new ArrayList<>();
        JsonNode se = f.get("sideEffects");
        if (se != null && se.isArray()) {
            for (JsonNode e : se) {
                String target = requiredText(e, "target");
                int dot = target.indexOf('.');
                if (dot <= 0) throw new IllegalArgumentException("side-effect target must be REG.FIELD: " + target);
                effects.add(new SideEffect(target.substring(0, dot), target.substring(dot + 1),
                        SideEffect.Action.fromString(requiredText(e, "action"))));
            }
        }
        return new BitField(name, offset, width, access, reset, effects, doc);
    }

    private static String text(JsonNode n, String key, String def) {
        JsonNode v = n.get(key);
        return v == null || v.isNull() ? def : v.asText();
    }

    private static String requiredText(JsonNode n, String key) {
        JsonNode v = n.get(key);
        if (v == null || v.isNull()) throw new IllegalArgumentException("missing required key: " + key);
        return v.asText();
    }

    private static long parseNumber(JsonNode n, String what) {
        if (n == null || n.isNull()) throw new IllegalArgumentException("missing number: " + what);
        return parseNumber(n, what, 0);
    }

    private static long parseNumber(JsonNode n, String what, long def) {
        if (n == null || n.isNull()) return def;
        if (n.isNumber()) return n.longValue();
        String s = n.asText().trim();
        try {
            return Long.decode(s);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("bad number for " + what + ": " + s);
        }
    }

    /** Canonical string form: depends only on model semantics, never on YAML key order. */
    public static String canonicalForm(ChipModel m) {
        StringBuilder sb = new StringBuilder();
        sb.append("chip ").append(m.name()).append(" endian=").append(m.endianness()).append('\n');
        for (RegisterDef r : m.registers()) {
            sb.append("reg ").append(r.name())
              .append(" @0x").append(Long.toHexString(r.address()))
              .append(" w=").append(r.width())
              .append(" reset=0x").append(Long.toHexString(r.reset()))
              .append(" order=").append(r.wordOrder());
            if (r.isAlias()) sb.append(" aliasOf=").append(r.aliasOf()).append(" semantic=").append(r.semantic());
            if (r.lockedBy() != null) {
                sb.append(" lockedBy=").append(r.lockedBy().register()).append('.')
                  .append(r.lockedBy().field()).append("==").append(r.lockedBy().openValue());
            }
            sb.append('\n');
            for (BitField f : r.fields()) {
                sb.append("  field ").append(f.name())
                  .append(" [").append(f.offset()).append('+').append(f.width()).append(']')
                  .append(' ').append(f.access());
                if (f.reset() != null) sb.append(" reset=0x").append(Long.toHexString(f.reset()));
                for (SideEffect se : f.sideEffects()) {
                    sb.append(" fx=").append(se.targetKey()).append(':').append(se.action());
                }
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    public static String fingerprint(ChipModel m) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(canonicalForm(m).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
