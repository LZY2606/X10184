package com.regmold.codegen;

import com.regmold.model.*;

import static com.regmold.codegen.Codegen.trace;

/** Markdown documentation for the register model. */
public class DocGenerator {

    private final ChipModel m;
    private final String version;
    private final String fp;

    public DocGenerator(ChipModel m, String version, String fp) {
        this.m = m;
        this.version = version;
        this.fp = fp;
    }

    public String markdown() {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(m.name()).append(" register reference\n\n");
        sb.append("- model revision: `").append(version).append("`\n");
        sb.append("- fingerprint: `").append(fp).append("`\n");
        sb.append("- endianness: `").append(m.endianness()).append("`\n\n");
        sb.append("| Register | Address | Width | Reset | Notes |\n|---|---|---|---|\n");
        for (RegisterDef r : m.registers()) {
            sb.append("| ").append(r.name())
              .append(" | 0x").append(Long.toHexString(r.address()))
              .append(" | ").append(r.width())
              .append(" | 0x").append(Long.toHexString(r.reset()))
              .append(" | ").append(notes(r)).append(" |\n");
        }
        sb.append('\n');
        for (RegisterDef r : m.registers()) {
            sb.append("## ").append(r.name()).append("\n\n");
            if (!r.doc().isBlank()) sb.append(r.doc()).append("\n\n");
            sb.append("address `0x").append(Long.toHexString(r.address())).append("`, width ").append(r.width())
              .append(", reset `0x").append(Long.toHexString(r.reset())).append('`');
            if (r.words() > 1) sb.append(", word order `").append(r.wordOrder()).append('`');
            sb.append("\n\n");
            if (r.lockedBy() != null) {
                LockSpec lk = r.lockedBy();
                sb.append("Writes locked unless `").append(lk.register()).append('.').append(lk.field())
                  .append(" == ").append(lk.openValue()).append("`.\n\n");
            }
            sb.append("| Field | Bits | Access | Reset | Side effects |\n|---|---|---|---|---|\n");
            for (BitField f : r.fields()) {
                String bits = f.width() == 1 ? String.valueOf(f.offset())
                        : (f.offset() + f.width() - 1) + ":" + f.offset();
                StringBuilder fx = new StringBuilder();
                for (SideEffect se : f.sideEffects()) fx.append(se.action()).append(' ').append(se.targetKey()).append("; ");
                sb.append("| ").append(f.name()).append(" | ").append(bits)
                  .append(" | ").append(f.access())
                  .append(" | ").append(f.reset() == null ? "-" : "0x" + Long.toHexString(f.reset()))
                  .append(" | ").append(fx).append(" |\n");
            }
            sb.append('\n');
        }
        sb.append("---\n").append(trace(m, version, fp)).append('\n');
        return sb.toString();
    }

    private String notes(RegisterDef r) {
        StringBuilder sb = new StringBuilder();
        if (r.isAlias()) sb.append("alias of ").append(r.aliasOf()).append(" (").append(r.semantic()).append(") ");
        if (r.lockedBy() != null) sb.append("locked ");
        if (r.words() > 1) sb.append("multi-word ");
        return sb.toString().trim();
    }
}
