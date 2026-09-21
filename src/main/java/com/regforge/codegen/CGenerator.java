package com.regforge.codegen;

import com.regforge.model.AccessMode;
import com.regforge.model.AddressSpace;
import com.regforge.model.ChipModel;
import com.regforge.model.FieldDef;
import com.regforge.model.RegisterDef;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 生成单头文件形式的 C 访问层：全部掩码、偏移与写序列都标注模型版本哈希。
 */
public class CGenerator {

    public Map<String, String> generate(ChipModel model, String hash) {
        String chip = sanitize(model.name());
        StringBuilder sb = new StringBuilder();
        sb.append("/*\n")
                .append(" * 由 regforge 寄存器铸模自动生成，请勿手工编辑。\n")
                .append(" * 模型: ").append(model.name()).append("  版本: ").append(hash).append('\n')
                .append(" * 端序: ").append(model.endianness()).append("\n")
                .append(" */\n")
                .append("#ifndef REGFORGE_").append(chip.toUpperCase()).append("_REGS_H\n")
                .append("#define REGFORGE_").append(chip.toUpperCase()).append("_REGS_H\n\n")
                .append("#include <stdint.h>\n\n");

        for (AddressSpace space : model.addressSpaces()) {
            sb.append("/* ===== 地址空间 ").append(space.name())
                    .append(" @ ").append(hex(space.base())).append(" ===== */\n\n");
            for (RegisterDef reg : space.registers()) {
                emitRegister(sb, chip, reg, hash);
            }
        }
        sb.append("#endif /* REGFORGE_").append(chip.toUpperCase()).append("_REGS_H */\n");
        Map<String, String> files = new LinkedHashMap<>();
        files.put("c/" + chip + "_regs.h", sb.toString());
        return files;
    }

    private void emitRegister(StringBuilder sb, String chip, RegisterDef reg, String hash) {
        String prefix = (chip + "_" + sanitize(reg.name())).toUpperCase();
        String fnPrefix = chip + "_" + sanitize(reg.name()).toLowerCase();
        sb.append("/* ---- ").append(reg.name())
                .append(" @ ").append(hex(reg.address()))
                .append(" width=").append(reg.width());
        if (reg.isAlias()) {
            sb.append(" aliasOf=").append(reg.aliasOf());
        }
        if (reg.lockedBy() != null) {
            sb.append(" lockedBy=").append(reg.lockedBy());
        }
        sb.append(" (model ").append(hash).append(") ---- */\n");
        sb.append("#define ").append(prefix).append("_OFFSET ")
                .append(hex(reg.address())).append("u /* model ").append(hash).append(" */\n");
        sb.append("#define ").append(prefix).append("_RESET  ")
                .append(hexWidth(reg.reset(), reg.width())).append("u\n");
        sb.append("#define ").append(prefix).append("_WIDTH  ")
                .append(reg.width()).append("u\n");
        for (FieldDef field : reg.fields()) {
            String fp = prefix + "_" + sanitize(field.name()).toUpperCase();
            sb.append("/* ").append(reg.name()).append('.').append(field.name())
                    .append(" [").append(field.msb()).append(':').append(field.lsb())
                    .append("] ").append(field.access())
                    .append(" (model ").append(hash).append(") */\n");
            sb.append("#define ").append(fp).append("_MASK  ")
                    .append(hexWidth(field.mask(), reg.width())).append("u\n");
            sb.append("#define ").append(fp).append("_SHIFT ").append(field.lsb()).append("u\n");
        }
        sb.append('\n');

        boolean wide = reg.width() > 32;
        String uType = wide ? "uint64_t" : uintType(reg.width());
        sb.append("static inline ").append(uType).append(' ')
                .append(fnPrefix).append("_read(volatile const void *base) {\n");
        if (!wide) {
            sb.append("    volatile const uint8_t *p = (volatile const uint8_t *)base + ")
                    .append(prefix).append("_OFFSET;\n");
            sb.append("    return *(volatile const ").append(uType).append(" *)p;\n");
        } else {
            sb.append("    volatile const uint8_t *p = (volatile const uint8_t *)base + ")
                    .append(prefix).append("_OFFSET;\n");
            sb.append("    /* 多字读取次序: ")
                    .append(reg.wordOrder() == RegisterDef.WordOrder.HIGH_FIRST
                            ? "高字优先 (model " : "低字优先 (model ")
                    .append(hash).append(") */\n");
            if (reg.wordOrder() == RegisterDef.WordOrder.LOW_FIRST) {
                sb.append("    uint32_t lo = *(volatile const uint32_t *)(p + 0u);\n");
                sb.append("    uint32_t hi = *(volatile const uint32_t *)(p + 4u);\n");
            } else {
                sb.append("    uint32_t hi = *(volatile const uint32_t *)(p + 0u);\n");
                sb.append("    uint32_t lo = *(volatile const uint32_t *)(p + 4u);\n");
            }
            sb.append("    return ((uint64_t)hi << 32) | (uint64_t)lo;\n");
        }
        sb.append("}\n\n");

        sb.append("static inline void ").append(fnPrefix)
                .append("_write(volatile void *base, ").append(uType).append(" value) {\n");
        if (!wide) {
            sb.append("    volatile uint8_t *p = (volatile uint8_t *)base + ")
                    .append(prefix).append("_OFFSET;\n");
            sb.append("    *(volatile ").append(uType).append(" *)p = value;\n");
        } else {
            sb.append("    volatile uint8_t *p = (volatile uint8_t *)base + ")
                    .append(prefix).append("_OFFSET;\n");
            sb.append("    /* 多字写入次序与读取一致 (model ").append(hash).append(") */\n");
            if (reg.wordOrder() == RegisterDef.WordOrder.LOW_FIRST) {
                sb.append("    *(volatile uint32_t *)(p + 0u) = (uint32_t)(value);\n");
                sb.append("    *(volatile uint32_t *)(p + 4u) = (uint32_t)(value >> 32);\n");
            } else {
                sb.append("    *(volatile uint32_t *)(p + 0u) = (uint32_t)(value >> 32);\n");
                sb.append("    *(volatile uint32_t *)(p + 4u) = (uint32_t)(value);\n");
            }
        }
        sb.append("}\n\n");

        for (FieldDef field : reg.fields()) {
            emitFieldAccessors(sb, chip, reg, field, hash);
        }
    }

    private void emitFieldAccessors(StringBuilder sb, String chip, RegisterDef reg,
                                   FieldDef field, String hash) {
        String fp = (chip + "_" + sanitize(reg.name()) + "_" + sanitize(field.name()))
                .toUpperCase();
        String fn = chip + "_" + sanitize(reg.name()).toLowerCase()
                + "_" + sanitize(field.name()).toLowerCase();
        String uType = reg.width() > 32 ? "uint64_t" : uintType(reg.width());
        sb.append("/* 读取移位后的 ").append(field.access())
                .append(" 字段 (model ").append(hash).append(") */\n");
        sb.append("static inline ").append(reg.width() > 32 ? "uint64_t" : "uint32_t")
                .append(' ').append(fn).append("_get(").append(uType).append(" regval) {\n");
        sb.append("    return (uint32_t)((regval & ").append(fp)
                .append("_MASK) >> ").append(fp).append("_SHIFT);\n}\n\n");

        switch (field.access()) {
            case RW -> {
                sb.append("/* 原子入口: RMW 置位 ").append(reg.name()).append('.')
                        .append(field.name()).append(" (model ").append(hash).append(") */\n");
                sb.append("static inline void ").append(fn)
                        .append("_set(volatile void *base) {\n");
                sb.append("    ").append(uType).append(" v = ")
                        .append(chip + "_" + sanitize(reg.name()).toLowerCase())
                        .append("_read(base);\n");
                sb.append("    v |= ").append(fp).append("_MASK;\n");
                sb.append("    ").append(chip + "_" + sanitize(reg.name()).toLowerCase())
                        .append("_write(base, v);\n}\n\n");
                sb.append("/* 原子入口: RMW 清零 (model ").append(hash).append(") */\n");
                sb.append("static inline void ").append(fn)
                        .append("_clear(volatile void *base) {\n");
                sb.append("    ").append(uType).append(" v = ")
                        .append(chip + "_" + sanitize(reg.name()).toLowerCase())
                        .append("_read(base);\n");
                sb.append("    v &= ~").append(fp).append("_MASK;\n");
                sb.append("    ").append(chip + "_" + sanitize(reg.name()).toLowerCase())
                        .append("_write(base, v);\n}\n\n");
            }
            case W1S -> {
                sb.append("/* 原子入口: 写一置位（直接写掩码，无 RMW）(model ")
                        .append(hash).append(") */\n");
                sb.append("static inline void ").append(fn)
                        .append("_set(volatile void *base) {\n");
                sb.append("    ").append(chip + "_" + sanitize(reg.name()).toLowerCase())
                        .append("_write(base, (").append(uType).append(")").append(fp)
                        .append("_MASK);\n}\n\n");
            }
            case W1C -> {
                sb.append("/* 原子入口: 写一清零（直接写掩码，无 RMW）(model ")
                        .append(hash).append(") */\n");
                sb.append("static inline void ").append(fn)
                        .append("_clear(volatile void *base) {\n");
                sb.append("    ").append(chip + "_" + sanitize(reg.name()).toLowerCase())
                        .append("_write(base, (").append(uType).append(")").append(fp)
                        .append("_MASK);\n}\n\n");
            }
            default -> {
                // RO / RC / RESERVED 不生成可变入口
            }
        }
    }

    public static String uintType(int width) {
        if (width <= 8) {
            return "uint8_t";
        }
        if (width <= 16) {
            return "uint16_t";
        }
        return "uint32_t";
    }

    public static String sanitize(String raw) {
        return raw.replaceAll("[^A-Za-z0-9_]", "_");
    }

    public static String hex(long value) {
        return "0x" + Long.toUnsignedString(value, 16);
    }

    public static String hexWidth(long value, int width) {
        int digits = Math.max(1, (width + 3) / 4);
        String raw = Long.toUnsignedString(value, 16);
        return "0x" + "0".repeat(Math.max(0, digits - raw.length())) + raw;
    }
}
