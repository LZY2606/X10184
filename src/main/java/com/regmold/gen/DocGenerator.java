package com.regmold.gen;

import com.regmold.domain.Effect;
import com.regmold.domain.Field;
import com.regmold.domain.LockRule;
import com.regmold.domain.Model;
import com.regmold.domain.Register;

import java.util.List;

/** Human-reviewed markdown documentation of the mold. */
public final class DocGenerator {

    private DocGenerator() {
    }

    public static List<GenFile> generate(Model model, String modelHash) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(model.name()).append(" — Register Mold\n\n");
        sb.append("- Model version: `").append(model.version()).append("`\n");
        sb.append("- Canonical SHA-256: `").append(modelHash).append("`\n");
        sb.append("- Revision id: `").append(model.revisionId()).append("`\n");
        sb.append("- Address space: ").append(model.addressBits()).append(" bits\n");
        sb.append("- Bus word: ").append(model.wordBytes()).append(" bytes\n");
        sb.append("- Endianness: ").append(model.endianness().name().toLowerCase()).append("\n\n");

        sb.append("## Registers\n\n");
        for (Register r : model.registers()) {
            sb.append("### `").append(r.name()).append("`\n\n");
            sb.append("- Address: `0x").append(Long.toUnsignedString(r.address(), 16)).append("`\n");
            sb.append("- Width: ").append(r.widthBits()).append(" bits\n");
            sb.append("- Access: `").append(r.access().key()).append("`\n");
            if (r.isAlias()) {
                sb.append("- Alias of: `").append(r.aliasOf()).append("`\n");
            }
            if (r.readOrder() != null) {
                sb.append("- Read order: **").append(r.readOrder().key()).append("**\n");
            }
            if (r.description() != null && !r.description().isBlank()) {
                sb.append("- ").append(r.description()).append('\n');
            }
            sb.append("\n| Field | Bits | Access | Reset | Description |\n");
            sb.append("|---|---|---|---|---|\n");
            for (Field f : r.fields()) {
                String bits = f.width() == 1 ? String.valueOf(f.lsb())
                        : f.msb() + ".." + f.lsb();
                String desc = f.description() == null ? "" : f.description().replace("|", "\\|");
                sb.append("| `").append(f.name()).append("` | ").append(bits).append(" | `")
                        .append(f.access().key()).append("` | 0x")
                        .append(Long.toUnsignedString(f.reset(), 16)).append(" | ").append(desc).append(" |\n");
            }
            sb.append('\n');
        }

        if (!model.effects().isEmpty()) {
            sb.append("## Side effects\n\n");
            sb.append("| Trigger | Action | Target |\n|---|---|---|\n");
            for (Effect e : model.effects()) {
                sb.append("| `").append(e.trigger().canonical()).append("` | ")
                        .append(e.action().name().toLowerCase()).append(" | `")
                        .append(e.target().canonical()).append("` |\n");
            }
            sb.append('\n');
        }
        if (!model.locks().isEmpty()) {
            sb.append("## Locks\n\n");
            sb.append("| Target | Locked while |\n|---|---|\n");
            for (LockRule l : model.locks()) {
                sb.append("| `").append(l.target().canonical()).append("` | `")
                        .append(l.master().canonical()).append("` is set |\n");
            }
        }
        return List.of(new GenFile("docs/" + Naming.snake(model.name()) + "_registers.md", sb.toString()));
    }
}
