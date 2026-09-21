package com.regmold.io;

import com.regmold.domain.Effect;
import com.regmold.domain.Field;
import com.regmold.domain.LockRule;
import com.regmold.domain.Model;
import com.regmold.domain.Register;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Normalizes a parsed model and emits canonical YAML: the same semantic model always
 * serializes to the exact same bytes, regardless of input key order.
 */
public final class ModelCanonicalizer {

    private ModelCanonicalizer() {
    }

    public static Model normalize(Model m) {
        List<Register> regs = new ArrayList<>(m.registers());
        regs.sort(Comparator.comparingLong(Register::address).thenComparing(Register::name));
        List<Register> normalized = new ArrayList<>();
        for (Register r : regs) {
            List<Field> fields = new ArrayList<>(r.fields());
            fields.sort(Comparator.comparingInt(Field::lsb).thenComparing(Field::name));
            normalized.add(new Register(r.name(), r.address(), r.widthBits(), r.access(), r.reset(),
                    List.copyOf(fields), r.aliasOf(), r.readOrder(), r.description()));
        }
        return new Model(m.name(), m.version(), m.addressBits(), m.wordBytes(), m.endianness(),
                List.copyOf(normalized), List.copyOf(m.effects()), List.copyOf(m.locks()),
                m.sourceYaml(), m.revisionId(), m.canonicalYaml());
    }

    public static String canonicalYaml(Model m) {
        StringBuilder sb = new StringBuilder();
        sb.append("name: ").append(scalar(m.name())).append('\n');
        sb.append("version: ").append(scalar(m.version())).append('\n');
        sb.append("address_bits: ").append(m.addressBits()).append('\n');
        sb.append("word_bytes: ").append(m.wordBytes()).append('\n');
        sb.append("endianness: ").append(m.endianness().name().toLowerCase()).append('\n');

        sb.append("registers:\n");
        for (Register r : m.registers()) {
            sb.append("  - name: ").append(scalar(r.name())).append('\n');
            sb.append("    address: 0x").append(Long.toUnsignedString(r.address(), 16)).append('\n');
            sb.append("    width_bits: ").append(r.widthBits()).append('\n');
            sb.append("    access: ").append(r.access().key()).append('\n');
            if (r.reset() != null) {
                sb.append("    reset: 0x").append(Long.toUnsignedString(r.reset(), 16)).append('\n');
            }
            if (r.aliasOf() != null) {
                sb.append("    alias_of: ").append(scalar(r.aliasOf())).append('\n');
            }
            if (r.readOrder() != null) {
                sb.append("    read_order: ").append(r.readOrder().key()).append('\n');
            }
            if (r.description() != null && !r.description().isBlank()) {
                sb.append("    description: ").append(scalar(r.description())).append('\n');
            }
            sb.append("    fields:\n");
            for (Field f : r.fields()) {
                sb.append("      - name: ").append(scalar(f.name())).append('\n');
                sb.append("        lsb: ").append(f.lsb()).append('\n');
                sb.append("        width: ").append(f.width()).append('\n');
                sb.append("        access: ").append(f.access().key()).append('\n');
                if (f.reset() != 0L) {
                    sb.append("        reset: 0x").append(Long.toUnsignedString(f.reset(), 16)).append('\n');
                }
                if (f.description() != null && !f.description().isBlank()) {
                    sb.append("        description: ").append(scalar(f.description())).append('\n');
                }
            }
        }

        if (!m.effects().isEmpty()) {
            sb.append("effects:\n");
            List<Effect> effects = new ArrayList<>(m.effects());
            effects.sort(Comparator.comparing((Effect e) -> e.trigger().canonical())
                    .thenComparing(e -> e.action().name())
                    .thenComparing(e -> e.target().canonical()));
            for (Effect e : effects) {
                sb.append("  - trigger: ").append(e.trigger().canonical()).append('\n');
                sb.append("    action: ").append(e.action().name().toLowerCase()).append('\n');
                sb.append("    target: ").append(e.target().canonical()).append('\n');
            }
        }
        if (!m.locks().isEmpty()) {
            sb.append("locks:\n");
            List<LockRule> locks = new ArrayList<>(m.locks());
            locks.sort(Comparator.comparing((LockRule l) -> l.target().canonical())
                    .thenComparing(l -> l.master().canonical()));
            for (LockRule l : locks) {
                sb.append("  - target: ").append(l.target().canonical()).append('\n');
                sb.append("    master: ").append(l.master().canonical()).append('\n');
            }
        }
        return sb.toString();
    }

    private static String scalar(String s) {
        if (s == null || s.isEmpty() || !s.matches("[A-Za-z0-9_.$/-][A-Za-z0-9_.$ /-]*")
                || "true".equalsIgnoreCase(s) || "false".equalsIgnoreCase(s)
                || s.matches("-?\\d+") || s.matches("0x[0-9a-fA-F]+")) {
            return "\"" + (s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"")) + "\"";
        }
        return s;
    }

    /** SHA-256 of the canonical text, used as model version. */
    public static String hash(String canonical) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

}
