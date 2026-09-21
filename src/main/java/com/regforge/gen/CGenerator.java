package com.regforge.gen;

import com.regforge.model.Field;
import com.regforge.model.FieldAccess;
import com.regforge.model.Register;
import com.regforge.model.RegisterModel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Generates a deterministic C access layer.
 * Every mask/shift constant and every write helper carries a traceability
 * header pointing at the model revision and content hash.
 */
@Component
public class CGenerator implements CodeGenerator {

    @Override
    public GenBundle generate(RegisterModel model, long revision) {
        List<Register> regs = sorted(model);
        String guard = NameUtil.upper(model.getName()) + "_REGS_H";
        StringBuilder h = new StringBuilder();
        StringBuilder c = new StringBuilder();

        h.append("/*\n * ").append(model.getName()).append(" register access layer (generated, do not edit)\n")
         .append(" * ").append(fingerprint(model, revision)).append("\n */\n")
         .append("#ifndef ").append(guard).append("\n#define ").append(guard).append("\n\n")
         .append("#include <stdint.h>\n\n#ifdef __cplusplus\nextern \"C\" {\n#endif\n\n")
         .append("/* Bus primitives supplied by the integrator */\n")
         .append("uint64_t ").append(fnPrefix(model)).append("bus_read(uint64_t addr);\n")
         .append("void ").append(fnPrefix(model)).append("bus_write(uint64_t addr, uint64_t data);\n\n");

        c.append("/*\n * ").append(model.getName()).append(" register access layer (generated, do not edit)\n")
         .append(" * ").append(fingerprint(model, revision)).append("\n */\n")
         .append("#include \"regs.h\"\n\n");

        for (Register r : regs) {
            emitRegister(model, r, h, c);
        }

        h.append("#ifdef __cplusplus\n}\n#endif\n\n#endif /* ").append(guard).append(" */\n");

        List<GeneratedFile> files = List.of(
                new GeneratedFile("c/regs.h", h.toString()),
                new GeneratedFile("c/regs.c", c.toString()));
        return new GenBundle(model.getName(), model.getVersion(), com.regforge.model.Canonical.hash(model), files);
    }

    private void emitRegister(RegisterModel model, Register r, StringBuilder h, StringBuilder c) {
        String p = prefix(model);
        String fp = fnPrefix(model);
        String rn = NameUtil.upper(r.getName());
        String fn = NameUtil.lower(r.getName());
        h.append("/* ").append(r.getName()).append(": ")
         .append(r.getDescription().replace('\n', ' ')).append(" */\n");
        h.append("#define ").append(p).append(rn).append("_ADDR (0x")
         .append(Long.toUnsignedString(r.getAddress(), 16)).append("ULL)\n");
        h.append("#define ").append(p).append(rn).append("_WIDTH (").append(r.getWidthBits()).append(")\n");
        if (r.isAlias()) {
            h.append("/* alias of ").append(r.getAliasOf()).append(" */\n");
        }
        List<Field> fields = new ArrayList<>(r.getFields());
        fields.sort(Comparator.comparingInt(Field::getLsb));
        for (Field f : fields) {
            String ftn = rn + "_" + NameUtil.upper(f.getName());
            h.append("#define ").append(p).append(ftn).append("_SHIFT (").append(f.getLsb()).append(")\n");
            h.append("#define ").append(p).append(ftn).append("_MASK (0x")
             .append(Long.toUnsignedString(f.mask(), 16)).append("ULL) /* ")
             .append(f.getAccess()).append(" */\n");
        }
        if (r.getSetAlias() != null) {
            h.append("void ").append(fp).append(fn).append("_atomic_set(uint64_t mask);\n");
        }
        if (r.getClrAlias() != null) {
            h.append("void ").append(fp).append(fn).append("_atomic_clear(uint64_t mask);\n");
        }
        h.append("uint64_t ").append(fp).append(fn).append("_read(void);\n");
        h.append("uint64_t ").append(fp).append(fn).append("_read_consume(void);\n");
        h.append("void ").append(fp).append(fn).append("_write(uint64_t value);\n\n");

        // read with bus ordering
        int words = r.wordCount(model.getWordBits());
        c.append("uint64_t ").append(fp).append(fn).append("_read(void) {\n");
        if (words == 1) {
            c.append("    return ").append(fp).append("bus_read(").append(p).append(rn).append("_ADDR);\n");
        } else {
            c.append("    uint64_t v = 0;\n");
            for (int idx = 0; idx < words; idx++) {
                int logical = r.highFirst() ? (words - 1 - idx) : idx;
                c.append("    v |= (").append(fp).append("bus_read(").append(p).append(rn)
                 .append("_ADDR + ").append(logical * (model.getWordBits() / 8))
                 .append("ULL) & 0x").append(Long.toUnsignedString(model.wordMask(), 16)).append("ULL) << ")
                 .append(logical * model.getWordBits()).append(";\n");
            }
            c.append("    return v;\n");
        }
        c.append("}\n\n");

        c.append("uint64_t ").append(fp).append(fn).append("_read_consume(void) {\n")
         .append("    uint64_t v = ").append(fp).append(fn).append("_read();\n");
        boolean hasRc = fields.stream().anyMatch(f -> f.getAccess() == FieldAccess.RC);
        if (hasRc) {
            c.append("    uint64_t rc = 0");
            for (Field f : fields) {
                if (f.getAccess() == FieldAccess.RC) {
                    c.append(" | ").append(p).append(rn).append("_").append(NameUtil.upper(f.getName())).append("_MASK");
                }
            }
            c.append(";\n");
            c.append("    ").append(fp).append(fn).append("_write(v & ~rc);\n");
        }
        c.append("    return v;\n}\n\n");

        // write preserves reserved bits and respects access semantics
        c.append("void ").append(fp).append(fn).append("_write(uint64_t value) {\n");
        boolean valueUsed = fields.stream().anyMatch(f -> f.getAccess() == FieldAccess.RW
                || f.getAccess() == FieldAccess.W1C || f.getAccess() == FieldAccess.W1S);
        if (!valueUsed) c.append("    (void)value;\n");
        boolean needRead = fields.stream().anyMatch(f -> f.getAccess() == FieldAccess.RESERVED
                || f.getAccess() == FieldAccess.W1C || f.getAccess() == FieldAccess.W1S);
        long reservedMask = 0L;
        for (Field f : fields) {
            if (f.getAccess() == FieldAccess.RESERVED) reservedMask |= f.mask();
        }
        if (needRead) {
            c.append("    uint64_t cur = ").append(fp).append(fn).append("_read();\n");
        }
        c.append("    uint64_t out = 0");
        if (reservedMask != 0) {
            c.append(" | (cur & 0x").append(Long.toUnsignedString(reservedMask, 16)).append("ULL)");
        }
        c.append(";\n");
        for (Field f : fields) {
            String ftn = p + rn + "_" + NameUtil.upper(f.getName());
            switch (f.getAccess()) {
                case RW -> c.append("    out = (out & ~").append(ftn).append("_MASK) | (value & ")
                           .append(ftn).append("_MASK);\n");
                case W1S -> c.append("    out |= cur & ").append(ftn).append("_MASK; out |= value & ")
                            .append(ftn).append("_MASK;\n");
                case W1C -> c.append("    out |= cur & ").append(ftn).append("_MASK; out &= ~(value & ")
                            .append(ftn).append("_MASK);\n");
                case RESERVED -> c.append("    /* reserved bits already preserved from cur */\n");
                default -> c.append("    /* ").append(f.getAccess()).append(" field ")
                           .append(f.getName()).append(" ignores writes */\n");
            }
        }
        c.append("    ").append(fp).append("bus_write(").append(p).append(rn).append("_ADDR, out);\n");
        c.append("}\n\n");

        if (r.getSetAlias() != null) {
            Register set = model.findRegister(r.getSetAlias());
            if (set != null) {
                c.append("void ").append(fp).append(fn).append("_atomic_set(uint64_t mask) {\n")
                 .append("    ").append(fp).append("bus_write(").append(p)
                 .append(NameUtil.upper(set.getName())).append("_ADDR, mask);\n}\n\n");
            }
        }
        if (r.getClrAlias() != null) {
            Register clr = model.findRegister(r.getClrAlias());
            if (clr != null) {
                c.append("void ").append(fp).append(fn).append("_atomic_clear(uint64_t mask) {\n")
                 .append("    ").append(fp).append("bus_write(").append(p)
                 .append(NameUtil.upper(clr.getName())).append("_ADDR, mask);\n}\n\n");
            }
        }
    }

    private String prefix(RegisterModel m) {
        return NameUtil.upper(m.getName()) + "_";
    }

    private String fnPrefix(RegisterModel m) {
        return NameUtil.lower(m.getName()) + "_";
    }

    private List<Register> sorted(RegisterModel m) {
        List<Register> regs = new ArrayList<>(m.getRegisters());
        regs.sort(Comparator.comparingLong(Register::getAddress).thenComparing(Register::getName));
        return regs;
    }
}
