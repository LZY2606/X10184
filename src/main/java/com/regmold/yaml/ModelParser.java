package com.regmold.yaml;

import com.regmold.model.AccessType;
import com.regmold.model.Field;
import com.regmold.model.ModelException;
import com.regmold.model.Register;
import com.regmold.model.RegisterModel;
import com.regmold.model.SideEffect;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;

/**
 * Parses register-model YAML into a canonical {@link RegisterModel}.
 * All collections are sorted deterministically so that semantically equal
 * documents produce equal models regardless of YAML key or list order.
 */
public class ModelParser {

    public RegisterModel parse(String text) {
        Object doc;
        try {
            doc = new Yaml().load(text);
        } catch (Exception e) {
            throw new ModelException("invalid YAML: " + e.getMessage());
        }
        if (!(doc instanceof Map<?, ?> root)) {
            throw new ModelException("document root must be a mapping");
        }
        List<String> errors = new ArrayList<>();

        String name = str(root, "name", "unnamed");
        String endianness = str(root, "endianness", "little").toLowerCase();
        if (!endianness.equals("little") && !endianness.equals("big")) {
            errors.add("endianness must be 'little' or 'big'");
        }
        int wordWidth = (int) num(root, "word_width", 32);
        if (wordWidth != 8 && wordWidth != 16 && wordWidth != 32) {
            errors.add("word_width must be 8, 16 or 32");
        }

        List<Register> registers = new ArrayList<>();
        Object regsObj = root.get("registers");
        if (regsObj instanceof List<?> regList) {
            for (Object item : regList) {
                if (item instanceof Map<?, ?> rm) {
                    registers.add(parseRegister(rm, wordWidth, errors));
                } else {
                    errors.add("register entry must be a mapping");
                }
            }
        } else {
            errors.add("'registers' must be a list");
        }

        List<SideEffect> sideEffects = new ArrayList<>();
        Object seObj = root.get("side_effects");
        if (seObj instanceof List<?> seList) {
            for (Object item : seList) {
                if (item instanceof Map<?, ?> sm) {
                    sideEffects.add(parseSideEffect(sm, errors));
                } else {
                    errors.add("side_effect entry must be a mapping");
                }
            }
        }

        validate(registers, sideEffects, errors);
        if (!errors.isEmpty()) {
            throw new ModelException(errors);
        }

        registers.sort(Comparator.comparingLong(Register::address).thenComparing(Register::name));
        sideEffects.sort(Comparator.comparing(SideEffect::describe));
        return new RegisterModel(name, endianness, wordWidth,
                List.copyOf(registers), List.copyOf(sideEffects));
    }

    private Register parseRegister(Map<?, ?> rm, int wordWidth, List<String> errors) {
        String name = str(rm, "name", null);
        if (name == null) {
            errors.add("register missing 'name'");
            name = "?";
        }
        long address = num(rm, "address", 0);
        int width = (int) num(rm, "width", wordWidth);
        if (width != 8 && width != 16 && width != 32 && width != 64) {
            errors.add(name + ": width must be 8, 16, 32 or 64");
        }
        long reset = num(rm, "reset", 0);
        String aliasOf = str(rm, "alias_of", null);
        Register.AtomicOp atomicOp = Register.AtomicOp.NONE;
        String atomicStr = str(rm, "atomic_op", null);
        if (atomicStr != null) {
            switch (atomicStr.toLowerCase()) {
                case "set" -> atomicOp = Register.AtomicOp.SET;
                case "clear" -> atomicOp = Register.AtomicOp.CLEAR;
                default -> errors.add(name + ": atomic_op must be 'set' or 'clear'");
            }
        }
        String atomicTarget = str(rm, "atomic_target", null);
        Register.ReadOrder readOrder = "high_first".equalsIgnoreCase(str(rm, "read_order", "low_first"))
                ? Register.ReadOrder.HIGH_FIRST : Register.ReadOrder.LOW_FIRST;
        boolean latchOnRead = bool(rm, "latch_on_read", false);
        String lockedBy = str(rm, "locked_by", null);

        List<Field> fields = new ArrayList<>();
        Object fObj = rm.get("fields");
        if (fObj instanceof List<?> fList) {
            for (Object item : fList) {
                if (item instanceof Map<?, ?> fm) {
                    fields.add(parseField(name, fm, width, errors));
                } else {
                    errors.add(name + ": field entry must be a mapping");
                }
            }
        }
        fields.sort(Comparator.comparingInt(Field::offset).thenComparing(Field::name));
        return new Register(name, address, width, reset, aliasOf, atomicOp, atomicTarget,
                readOrder, latchOnRead, lockedBy, List.copyOf(fields));
    }

    private Field parseField(String regName, Map<?, ?> fm, int regWidth, List<String> errors) {
        String name = str(fm, "name", null);
        if (name == null) {
            errors.add(regName + ": field missing 'name'");
            name = "?";
        }
        int offset = (int) num(fm, "offset", 0);
        int width = (int) num(fm, "width", 1);
        if (width < 1 || offset < 0 || offset + width > regWidth) {
            errors.add(regName + "." + name + ": bits [" + (offset + width - 1) + ":" + offset
                    + "] outside register width " + regWidth);
        }
        AccessType access;
        try {
            access = AccessType.from(str(fm, "access", "rw"));
        } catch (IllegalArgumentException e) {
            errors.add(regName + "." + name + ": " + e.getMessage());
            access = AccessType.RW;
        }
        return new Field(name, offset, width, access);
    }

    private SideEffect parseSideEffect(Map<?, ?> sm, List<String> errors) {
        Map<?, ?> when = sm.get("when") instanceof Map<?, ?> w ? w : Map.of();
        Map<?, ?> then = sm.get("then") instanceof Map<?, ?> t ? t : Map.of();
        SideEffect se = new SideEffect(
                str(when, "reg", "?"), str(when, "field", "?"), num(when, "value", 1),
                str(then, "reg", "?"), str(then, "field", "?"), num(then, "value", 1));
        if (when.isEmpty() || then.isEmpty()) {
            errors.add("side_effect requires 'when' and 'then' mappings");
        }
        return se;
    }

    private void validate(List<Register> regs, List<SideEffect> effects, List<String> errors) {
        for (int i = 0; i < regs.size(); i++) {
            Register r = regs.get(i);
            for (int j = i + 1; j < regs.size(); j++) {
                if (regs.get(j).name().equals(r.name())) {
                    errors.add("duplicate register name: " + r.name());
                }
            }
            for (int a = 0; a < r.fields().size(); a++) {
                for (int b = a + 1; b < r.fields().size(); b++) {
                    Field fa = r.fields().get(a);
                    Field fb = r.fields().get(b);
                    if (fa.name().equals(fb.name())) {
                        errors.add(r.name() + ": duplicate field name " + fa.name());
                    }
                    boolean bothReserved = fa.access() == AccessType.RESERVED
                            && fb.access() == AccessType.RESERVED;
                    if (!bothReserved && fa.overlaps(fb)) {
                        errors.add(r.name() + ": fields " + fa.name() + " and " + fb.name() + " overlap");
                    }
                }
            }
            if (r.aliasOf() != null) {
                Register base = find(regs, r.aliasOf());
                if (base == null) {
                    errors.add(r.name() + ": alias_of unknown register " + r.aliasOf());
                } else if (base.address() != r.address()) {
                    errors.add(r.name() + ": alias must share address with " + r.aliasOf());
                }
            }
            if (r.atomicOp() != Register.AtomicOp.NONE && r.atomicTarget() == null && r.aliasOf() == null) {
                errors.add(r.name() + ": atomic_op requires atomic_target or alias_of");
            }
            if (r.atomicTarget() != null && find(regs, r.atomicTarget()) == null) {
                errors.add(r.name() + ": atomic_target unknown register " + r.atomicTarget());
            }
            if (r.lockedBy() != null) {
                String[] parts = r.lockedBy().split("\\.");
                Register lockReg = parts.length == 2 ? find(regs, parts[0]) : null;
                if (lockReg == null || lockReg.field(parts[1]) == null) {
                    errors.add(r.name() + ": locked_by must reference REG.FIELD, got " + r.lockedBy());
                }
            }
        }
        for (SideEffect e : effects) {
            Register tr = find(regs, e.triggerReg());
            Register tg = find(regs, e.targetReg());
            if (tr == null || tr.field(e.triggerField()) == null) {
                errors.add("side_effect trigger unknown: " + e.triggerReg() + "." + e.triggerField());
            }
            if (tg == null || tg.field(e.targetField()) == null) {
                errors.add("side_effect target unknown: " + e.targetReg() + "." + e.targetField());
            }
        }
    }

    private static Register find(List<Register> regs, String name) {
        for (Register r : regs) {
            if (r.name().equals(name)) {
                return r;
            }
        }
        return null;
    }

    /** Deterministic canonical form used for hashing and version comparison. */
    public static String canonical(RegisterModel m) {
        StringBuilder sb = new StringBuilder();
        sb.append("name=").append(m.name()).append('\n');
        sb.append("endianness=").append(m.endianness()).append('\n');
        sb.append("word_width=").append(m.wordWidth()).append('\n');
        for (Register r : m.registers()) {
            sb.append("reg ").append(r.name())
                    .append(" addr=0x").append(Long.toHexString(r.address()))
                    .append(" width=").append(r.width())
                    .append(" reset=0x").append(Long.toHexString(r.reset()))
                    .append(" alias=").append(r.aliasOf())
                    .append(" atomic=").append(r.atomicOp()).append(':').append(r.atomicTarget())
                    .append(" order=").append(r.readOrder())
                    .append(" latch=").append(r.latchOnRead())
                    .append(" lockedBy=").append(r.lockedBy())
                    .append('\n');
            for (Field f : r.fields()) {
                sb.append("  field ").append(f.name())
                        .append(" offset=").append(f.offset())
                        .append(" width=").append(f.width())
                        .append(" access=").append(f.access())
                        .append('\n');
            }
        }
        for (SideEffect e : m.sideEffects()) {
            sb.append("effect ").append(e.describe()).append('\n');
        }
        return sb.toString();
    }

    public static String hash(RegisterModel m) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical(m).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String str(Map<?, ?> m, String key, String def) {
        Object v = m.get(key);
        return v == null ? def : String.valueOf(v);
    }

    private static boolean bool(Map<?, ?> m, String key, boolean def) {
        Object v = m.get(key);
        if (v == null) {
            return def;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        return Boolean.parseBoolean(String.valueOf(v));
    }

    static long num(Map<?, ?> m, String key, long def) {
        Object v = m.get(key);
        if (v == null) {
            return def;
        }
        return parseNum(v);
    }

    public static long parseNum(Object v) {
        if (v instanceof Number n) {
            return n.longValue();
        }
        String s = String.valueOf(v).trim().replace("_", "");
        if (s.startsWith("0x") || s.startsWith("0X")) {
            return Long.parseUnsignedLong(s.substring(2), 16);
        }
        if (s.startsWith("0b") || s.startsWith("0B")) {
            return Long.parseUnsignedLong(s.substring(2), 2);
        }
        return Long.parseLong(s);
    }
}
