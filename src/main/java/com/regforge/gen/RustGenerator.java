package com.regforge.gen;

import com.regforge.model.Canonical;
import com.regforge.model.Field;
import com.regforge.model.FieldAccess;
import com.regforge.model.Register;
import com.regforge.model.RegisterModel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Generates a deterministic, no_std-capable Rust access module. */
@Component
public class RustGenerator implements CodeGenerator {

    @Override
    public GenBundle generate(RegisterModel model, long revision) {
        List<Register> regs = sorted(model);
        StringBuilder sb = new StringBuilder();
        sb.append("// ").append(model.getName()).append(" register access layer (generated, do not edit)\n")
          .append("// ").append(fingerprint(model, revision)).append("\n")
          .append("/// Bus primitives supplied by the integrator.\n")
          .append("#[allow(unused)]\n")
          .append("pub trait Bus {\n")
          .append("    fn read(&mut self, addr: u64) -> u64;\n")
          .append("    fn write(&mut self, addr: u64, data: u64);\n")
          .append("}\n\n");

        for (Register r : regs) {
            emitRegister(model, r, sb);
        }
        sb.append("#[allow(dead_code)]\nfn _fingerprint() -> &'static str { \"")
          .append(Canonical.hash(model)).append("\" }\n");

        return new GenBundle(model.getName(), model.getVersion(), Canonical.hash(model),
                List.of(new GeneratedFile("rust/regs.rs", sb.toString())));
    }

    private void emitRegister(RegisterModel model, Register r, StringBuilder sb) {
        String rn = NameUtil.upper(r.getName());
        String fn = NameUtil.lower(r.getName());
        sb.append("/// ").append(r.getName()).append(": ")
          .append(r.getDescription().replace('\n', ' ')).append('\n');
        sb.append("pub mod ").append(fn).append(" {\n");
        sb.append("    pub const ADDR: u64 = 0x").append(Long.toUnsignedString(r.getAddress(), 16)).append(";\n");
        sb.append("    pub const WIDTH: u32 = ").append(r.getWidthBits()).append(";\n");
        List<Field> fields = new ArrayList<>(r.getFields());
        fields.sort(Comparator.comparingInt(Field::getLsb));
        for (Field f : fields) {
            sb.append("    /// `").append(f.getName()).append("` ").append(f.getAccess())
              .append(" bits ").append(f.getLsb()).append("..=").append(f.getMsb()).append('\n');
            sb.append("    pub mod ").append(NameUtil.lower(f.getName())).append(" {\n");
            sb.append("        pub const SHIFT: u32 = ").append(f.getLsb()).append(";\n");
            sb.append("        pub const MASK: u64 = 0x").append(Long.toUnsignedString(f.mask(), 16)).append(";\n");
            sb.append("    }\n");
        }
        int words = r.wordCount(model.getWordBits());
        sb.append("    /// Multi-word read honoring the declared word order (")
          .append(r.highFirst() ? "high-first" : "low-first").append(").\n");
        sb.append("    pub fn read<B: super::Bus>(bus: &mut B) -> u64 {\n");
        if (words == 1) {
            sb.append("        bus.read(ADDR)\n");
        } else {
            sb.append("        let mut value: u64 = 0;\n");
            for (int idx = 0; idx < words; idx++) {
                int logical = r.highFirst() ? (words - 1 - idx) : idx;
                sb.append("        value |= (bus.read(ADDR + ")
                  .append(logical * (model.getWordBits() / 8))
                  .append(") & 0x").append(Long.toUnsignedString(model.wordMask(), 16))
                  .append(") << ").append(logical * model.getWordBits()).append(";\n");
            }
            sb.append("        value\n");
        }
        sb.append("    }\n");

        sb.append("    /// Read and clear every RC field (consuming read).\n");
        sb.append("    pub fn read_consume<B: super::Bus>(bus: &mut B) -> u64 {\n");
        sb.append("        let value = read(bus);\n");
        List<Field> rc = fields.stream().filter(f -> f.getAccess() == FieldAccess.RC).toList();
        if (rc.isEmpty()) {
            sb.append("        let _ = bus;\n        value\n");
        } else {
            sb.append("        let mut rc_mask: u64 = 0;\n");
            for (Field f : rc) {
                sb.append("        rc_mask |= ").append(NameUtil.lower(f.getName())).append("::MASK;\n");
            }
            sb.append("        write(bus, value & !rc_mask);\n        value\n");
        }
        sb.append("    }\n");

        sb.append("    /// Write preserving reserved bits and applying W1S/W1C semantics.\n");
        sb.append("    pub fn write<B: super::Bus>(bus: &mut B, value: u64) {\n");
        boolean needCur = fields.stream().anyMatch(f -> f.getAccess() == FieldAccess.RESERVED
                || f.getAccess() == FieldAccess.W1C || f.getAccess() == FieldAccess.W1S);
        if (needCur) sb.append("        let cur = read(bus);\n");
        sb.append("        let mut out: u64 = 0");
        long reserved = 0L;
        for (Field f : fields) if (f.getAccess() == FieldAccess.RESERVED) reserved |= f.mask();
        if (reserved != 0) {
            sb.append(" | (cur & 0x").append(Long.toUnsignedString(reserved, 16)).append(")");
        }
        sb.append(";\n");
        for (Field f : fields) {
            String fmod = NameUtil.lower(f.getName()) + "::MASK";
            switch (f.getAccess()) {
                case RW -> sb.append("        out = (out & !").append(fmod).append(") | (value & ").append(fmod).append(");\n");
                case W1S -> sb.append("        out |= cur & ").append(fmod).append("; out |= value & ").append(fmod).append(";\n");
                case W1C -> sb.append("        out |= cur & ").append(fmod).append("; out &= !(value & ").append(fmod).append(");\n");
                case RESERVED -> sb.append("        // reserved bits preserved from cur\n");
                default -> sb.append("        // ").append(f.getAccess()).append(" field ignores writes\n");
            }
        }
        sb.append("        bus.write(ADDR, out);\n    }\n");

        if (r.getSetAlias() != null && model.findRegister(r.getSetAlias()) != null) {
            sb.append("    pub fn atomic_set<B: super::Bus>(bus: &mut B, mask: u64) {\n")
              .append("        bus.write(super::").append(NameUtil.lower(r.getSetAlias()))
              .append("::ADDR, mask);\n    }\n");
        }
        if (r.getClrAlias() != null && model.findRegister(r.getClrAlias()) != null) {
            sb.append("    pub fn atomic_clear<B: super::Bus>(bus: &mut B, mask: u64) {\n")
              .append("        bus.write(super::").append(NameUtil.lower(r.getClrAlias()))
              .append("::ADDR, mask);\n    }\n");
        }
        sb.append("}\n\n");
    }

    private List<Register> sorted(RegisterModel m) {
        List<Register> regs = new ArrayList<>(m.getRegisters());
        regs.sort(Comparator.comparingLong(Register::getAddress).thenComparing(Register::getName));
        return regs;
    }
}
