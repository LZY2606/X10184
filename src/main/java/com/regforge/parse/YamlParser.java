package com.regforge.parse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.regforge.model.Field;
import com.regforge.model.FieldAccess;
import com.regforge.model.FieldEffect;
import com.regforge.model.Register;
import com.regforge.model.RegisterModel;
import com.regforge.util.Numbers;
import com.regforge.validate.Issue;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Parses register YAML into a {@link RegisterModel}.
 *
 * <pre>
 * name, version, description
 * address_space: {bits, word_bits, endian}
 * registers:
 *   - name, address, width, reset, description, alias_of, read_order,
 *     locked_by, lock_level, set_alias, clr_alias
 *     fields:
 *       - name, bits (10:4 / "5" / [1,4]), reset, access, description
 *         effects: [{on_write: {set|clear|latch: "REG.FIELD"}}]
 * </pre>
 */
@Component
public class YamlParser {

    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    public ParseResult parse(String text) {
        List<Issue> issues = new ArrayList<>();
        RegisterModel model = new RegisterModel();
        Object rootObj;
        try {
            rootObj = yaml.readValue(text, Object.class);
        } catch (Exception e) {
            issues.add(Issue.error("YAML_SYNTAX", "YAML 解析失败: " + e.getMessage()));
            return new ParseResult(model, issues);
        }
        if (!(rootObj instanceof Map)) {
            issues.add(Issue.error("YAML_SHAPE", "顶层必须是映射 (map)"));
            return new ParseResult(model, issues);
        }
        Map<String, Object> root = Numbers.asMap(rootObj);

        model.setName(str(root.get("name"), "device"));
        model.setVersion(str(root.get("version"), "0.0.0"));
        model.setDescription(str(root.get("description"), ""));

        Map<String, Object> space = Numbers.asMap(root.get("address_space"));
        model.setAddressBits(Numbers.parseInt(space.getOrDefault("bits", root.get("address_bits")), 32));
        model.setWordBits(Numbers.parseInt(space.getOrDefault("word_bits", root.get("word_bits")), 32));
        String endian = str(firstNonNull(space.get("endian"), root.get("endian")), "little");
        model.setEndian(endian.toLowerCase());

        Object regs = firstNonNull(root.get("registers"), root.get("regs"));
        if (regs instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map) {
                    model.getRegisters().add(parseRegister(Numbers.asMap(item), issues));
                }
            }
        } else {
            issues.add(Issue.warning("NO_REGISTERS", "未找到 registers 列表"));
        }
        return new ParseResult(model, issues);
    }

    private Register parseRegister(Map<String, Object> m, List<Issue> issues) {
        Register r = new Register();
        r.setName(str(m.get("name"), ""));
        r.setDescription(str(m.get("description"), ""));
        r.setAddress(Numbers.parseLong(firstNonNull(m.get("address"), m.get("offset")), 0L));
        r.setWidthBits(Numbers.parseInt(firstNonNull(m.get("width"), m.get("width_bits")), 32));
        r.setReset(Numbers.parseLong(m.get("reset"), 0L));
        r.setAliasOf(blankToNull(str(m.get("alias_of"), null)));
        r.setReadOrder(str(m.get("read_order"), "low_first"));
        r.setLockedBy(blankToNull(str(m.get("locked_by"), null)));
        r.setLockLevel(str(m.get("lock_level"), "1"));
        r.setSetAlias(blankToNull(str(firstNonNull(m.get("set_alias"), m.get("set")), null)));
        r.setClrAlias(blankToNull(str(firstNonNull(m.get("clr_alias"), m.get("clear")), null)));

        Object fields = m.get("fields");
        if (fields instanceof List<?> list) {
            for (Object f : list) {
                if (f instanceof Map) {
                    parseField(r, Numbers.asMap(f), issues);
                }
            }
        }
        if (r.getName().isBlank()) {
            issues.add(Issue.error("REG_NO_NAME", "寄存器缺少 name", List.of("registers")));
        }
        return r;
    }

    private void parseField(Register r, Map<String, Object> m, List<Issue> issues) {
        Field f = new Field();
        f.setName(str(m.get("name"), ""));
        f.setDescription(str(m.get("description"), ""));
        f.setAccess(FieldAccess.fromString(str(m.get("access"), "rw")));
        f.setReset(Numbers.parseLong(m.get("reset"), 0L));

        int[] range = parseBits(m.get("bits"), m);
        f.setLsb(range[0]);
        f.setMsb(range[1]);

        Object effects = firstNonNull(m.get("effects"), m.get("side_effects"), m.get("triggers"));
        if (effects instanceof List<?> list) {
            for (Object e : list) {
                parseEffect(f, Numbers.asMap(e), issues);
            }
        } else if (effects instanceof Map) {
            parseEffect(f, Numbers.asMap(effects), issues);
        }
        r.getFields().add(f);
        if (f.getName().isBlank()) {
            issues.add(Issue.error("FIELD_NO_NAME",
                    "寄存器 " + r.getName() + " 中存在缺少 name 的位域",
                    List.of(r.getName())));
        }
    }

    private void parseEffect(Field f, Map<String, Object> m, List<Issue> issues) {
        Object onWrite = firstNonNull(m.get("on_write"), m.get("when_written"), m);
        Map<String, Object> body = Numbers.asMap(onWrite);
        for (String key : new String[]{"set", "clear", "latch"}) {
            Object target = body.get(key);
            if (target != null) {
                f.getEffects().add(new FieldEffect(key, str(target, "")));
            }
        }
        if (f.getEffects().isEmpty()) {
            issues.add(Issue.warning("EFFECT_EMPTY",
                    "位域 " + f.getName() + " 的副作用未声明 set/clear/latch", List.of(f.getName())));
        }
    }

    /** Accepts "10:4", "5", [lsb, msb], {lsb, msb}. */
    private int[] parseBits(Object raw, Map<String, Object> m) {
        if (raw instanceof String s && s.contains(":")) {
            String[] parts = s.split(":");
            int a = Numbers.parseInt(parts[0].trim(), 0);
            int b = Numbers.parseInt(parts[1].trim(), 0);
            return new int[]{Math.min(a, b), Math.max(a, b)};
        }
        if (raw instanceof List<?> list && list.size() >= 2) {
            int a = Numbers.parseInt(list.get(0), 0);
            int b = Numbers.parseInt(list.get(1), 0);
            return new int[]{Math.min(a, b), Math.max(a, b)};
        }
        if (raw != null) {
            int single = Numbers.parseInt(raw, 0);
            return new int[]{single, single};
        }
        int lsb = Numbers.parseInt(firstNonNull(m.get("lsb"), m.get("low")), 0);
        int msb = Numbers.parseInt(firstNonNull(m.get("msb"), m.get("high")), lsb);
        return new int[]{Math.min(lsb, msb), Math.max(lsb, msb)};
    }

    private static Object firstNonNull(Object... vals) {
        for (Object v : vals) if (v != null) return v;
        return null;
    }

    private static String str(Object o, String fallback) {
        return o == null ? fallback : String.valueOf(o);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
