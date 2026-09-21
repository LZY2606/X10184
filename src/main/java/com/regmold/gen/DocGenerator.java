package com.regmold.gen;

import com.regmold.domain.Diagnostic;
import com.regmold.domain.FieldModel;
import com.regmold.domain.Model;
import com.regmold.domain.RegisterModel;
import com.regmold.validate.ModelIndex;

import java.util.Comparator;
import java.util.List;

public class DocGenerator {

    public void generate(GenResult result, Model model, List<Diagnostic> diagnostics) {
        ModelIndex index = new ModelIndex(model);
        StringBuilder md = new StringBuilder();
        md.append("# ").append(model.name).append("\n\n");
        if (model.description != null && !model.description.isBlank()) {
            md.append(model.description).append("\n\n");
        }
        md.append("- data width: ").append(model.dataWidth).append(" bits\n");
        md.append("- endianness: ").append(model.endianness).append('\n');
        md.append("- semantic hash: `").append(result.semanticHash).append("`\n\n");
        md.append("## Registers\n\n");
        for (RegisterModel r : sorted(model)) {
            int width = index.width(r);
            md.append("### `").append(r.name).append("`\n\n");
            md.append("- offset: `0x").append(Long.toUnsignedString(r.offset, 16)).append('`');
            md.append(" / width: ").append(width).append(" bits");
            if (r.aliasOf != null) {
                md.append(" / alias of `").append(r.aliasOf).append("` (").append(r.aliasWrite).append(')');
            }
            if (r.lock != null) {
                md.append(" / locked by `").append(r.lock.register).append('[').append(r.lock.bit).append("]`");
            }
            md.append('\n');
            if (r.description != null && !r.description.isBlank()) {
                md.append('\n').append(r.description).append('\n');
            }
            if (!r.fields.isEmpty()) {
                md.append("\n| bits | field | access | reset | mask |\n");
                md.append("|------|-------|--------|-------|------|\n");
                for (FieldModel f : r.fields.stream().sorted(Comparator.comparingInt((FieldModel a) -> a.bitStart).reversed()).toList()) {
                    String range = f.bitCount == 1 ? String.valueOf(f.bitStart) : f.bitEnd() + ":" + f.bitStart;
                    md.append("| ").append(range)
                            .append(" | ").append(f.name)
                            .append(" | ").append(f.access)
                            .append(" | 0x").append(Long.toUnsignedString(f.reset, 16))
                            .append(" | `0x").append(Long.toUnsignedString(f.linearMask(), 16))
                            .append("` |\n");
                }
            }
            md.append('\n');
        }
        if (!diagnostics.isEmpty()) {
            md.append("## Validation\n\n");
            for (Diagnostic d : diagnostics) {
                md.append("- **").append(d.severity).append("** `").append(d.code).append("`: ").append(d.message);
                if (!d.path.isEmpty()) {
                    md.append(" — shortest conflict path: ").append(String.join(" → ", d.path));
                }
                md.append('\n');
            }
        }
        result.files.put("register_model.md", md.toString());
    }

    private List<RegisterModel> sorted(Model model) {
        return model.registers.stream().sorted(Comparator.comparing(a -> a.name)).toList();
    }
}
