package com.regforge.gen;

import com.regforge.model.Field;
import com.regforge.model.Register;
import com.regforge.model.RegisterModel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Generates deterministic Markdown documentation. */
@Component
public class DocGenerator implements CodeGenerator {

    @Override
    public GenBundle generate(RegisterModel model, long revision) {
        StringBuilder md = new StringBuilder();
        md.append("# ").append(model.getName()).append(" 寄存器说明\n\n")
          .append("> 生成物溯源：`").append(fingerprint(model, revision)).append("`\n\n");
        md.append("- 版本: ").append(model.getVersion()).append('\n')
          .append("- 地址位宽: ").append(model.getAddressBits()).append('\n')
          .append("- 数据字宽: ").append(model.getWordBits()).append('\n')
          .append("- 端序: ").append(model.getEndian()).append("\n\n");

        List<Register> regs = new ArrayList<>(model.getRegisters());
        regs.sort(Comparator.comparingLong(Register::getAddress).thenComparing(Register::getName));
        for (Register r : regs) {
            md.append("## ").append(r.getName());
            md.append(" @ `0x").append(Long.toUnsignedString(r.getAddress(), 16)).append("`\n\n");
            if (!r.getDescription().isBlank()) md.append(r.getDescription()).append("\n\n");
            md.append("- 宽度: ").append(r.getWidthBits()).append(" 位\n");
            md.append("- 复位值: `0x").append(Long.toUnsignedString(r.getReset(), 16)).append("`\n");
            if (r.isAlias()) md.append("- 别名 → `").append(r.getAliasOf()).append("`（共享存储，写语义见位域）\n");
            md.append("- 读取次序: ").append(r.highFirst() ? "高字优先" : "低字优先").append('\n');
            if (r.getLockedBy() != null) md.append("- 锁: `").append(r.getLockedBy())
                    .append(" == ").append(r.getLockLevel()).append("`\n");
            if (r.getSetAlias() != null) md.append("- 原子置位入口: `").append(r.getSetAlias()).append("`\n");
            if (r.getClrAlias() != null) md.append("- 原子清零入口: `").append(r.getClrAlias()).append("`\n");
            md.append('\n');
            md.append("| 位域 | 位 | 访问 | 复位 | 掩码 | 副作用 |\n");
            md.append("| --- | --- | --- | --- | --- | --- |\n");
            List<Field> fields = new ArrayList<>(r.getFields());
            fields.sort(Comparator.comparingInt(Field::getLsb));
            for (Field f : fields) {
                String bits = f.getLsb() == f.getMsb()
                        ? String.valueOf(f.getLsb())
                        : f.getMsb() + ":" + f.getLsb();
                String effects = f.getEffects().stream().map(e -> e.normalizedAction() + " `" + e.target() + "`")
                        .reduce((a, b) -> a + ", " + b).orElse("—");
                md.append("| ").append(f.getName())
                  .append(" | ").append(bits)
                  .append(" | ").append(f.getAccess())
                  .append(" | 0x").append(Long.toUnsignedString(f.getReset(), 16))
                  .append(" | `0x").append(Long.toUnsignedString(f.mask(), 16)).append("`")
                  .append(" | ").append(effects)
                  .append(" |\n");
            }
            md.append('\n');
        }
        return new GenBundle(model.getName(), model.getVersion(),
                com.regforge.model.Canonical.hash(model),
                List.of(new GeneratedFile("docs/registers.md", md.toString())));
    }
}
