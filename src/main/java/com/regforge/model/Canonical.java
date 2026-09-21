package com.regforge.model;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/**
 * Deterministic serialization of a model.  Two YAML files with identical
 * semantics but different key/entry order produce the same canonical text and
 * therefore the same content hash.
 */
public final class Canonical {

    private Canonical() {}

    public static String text(RegisterModel m) {
        List<Register> regs = new ArrayList<>(m.getRegisters());
        regs.sort(Comparator.comparingLong(Register::getAddress).thenComparing(Register::getName));
        StringBuilder sb = new StringBuilder();
        sb.append("model:").append(m.getName()).append('|').append(m.getVersion()).append('\n');
        sb.append("space:").append(m.getAddressBits()).append('/').append(m.getWordBits())
                .append('/').append(m.getEndian()).append('\n');
        for (Register r : regs) {
            sb.append("reg:").append(r.getName())
                    .append("@0x").append(Long.toUnsignedString(r.getAddress(), 16))
                    .append(" w=").append(r.getWidthBits())
                    .append(" reset=0x").append(Long.toUnsignedString(r.getReset(), 16))
                    .append(r.isAlias() ? " alias=" + r.getAliasOf() : "")
                    .append(" order=").append(r.getReadOrder())
                    .append(r.getLockedBy() != null ? " lock=" + r.getLockedBy() + ":" + r.getLockLevel() : "")
                    .append(r.getSetAlias() != null ? " set=" + r.getSetAlias() : "")
                    .append(r.getClrAlias() != null ? " clr=" + r.getClrAlias() : "")
                    .append('\n');
            List<Field> fields = new ArrayList<>(r.getFields());
            fields.sort(Comparator.comparingInt(Field::getLsb));
            for (Field f : fields) {
                sb.append("  field:").append(f.getName())
                        .append('[').append(f.getLsb()).append(':').append(f.getMsb()).append(']')
                        .append(" acc=").append(f.getAccess())
                        .append(" reset=0x").append(Long.toUnsignedString(f.getReset(), 16))
                        .append(" desc=").append(norm(f.getDescription()))
                        .append('\n');
                List<FieldEffect> effs = new ArrayList<>(f.getEffects());
                effs.sort(Comparator.comparing(FieldEffect::display));
                for (FieldEffect e : effs) {
                    sb.append("    effect:").append(e.normalizedAction()).append(' ').append(e.target()).append('\n');
                }
            }
        }
        return sb.toString();
    }

    public static String hash(RegisterModel m) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text(m).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().replaceAll("\\s+", " ");
    }
}
