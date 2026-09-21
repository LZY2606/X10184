package com.regforge.codegen;

import com.regforge.model.AccessMode;
import com.regforge.model.AddressSpace;
import com.regforge.model.ChipModel;
import com.regforge.model.FieldDef;
import com.regforge.model.RegisterDef;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 生成 no_std 风格 Rust 访问层；每个常量与写序列都标注模型版本哈希。
 */
public class RustGenerator {

    public Map<String, String> generate(ChipModel model, String hash) {
        String chip = CGenerator.sanitize(model.name()).toLowerCase();
        StringBuilder sb = new StringBuilder();
        sb.append("// 由 regforge 寄存器铸模自动生成，请勿手工编辑。\n")
                .append("// 模型: ").append(model.name()).append("  版本: ").append(hash).append('\n')
                .append("// 端序: ").append(model.endianness()).append('\n')
                .append("#[allow(dead_code)]\n")
                .append("pub mod ").append(chip).append(" {\n");
        for (AddressSpace space : model.addressSpaces()) {
            sb.append("    // ===== 地址空间 ").append(space.name())
                    .append(" @ ").append(CGenerator.hex(space.base())
                            .replace("0x", "0x"))
                    .append(" =====\n\n");
            for (RegisterDef reg : space.registers()) {
                emitRegister(sb, chip, reg, hash);
            }
        }
        sb.append("}\n");
        Map<String, String> files = new LinkedHashMap<>();
        files.put("rust/" + chip + "_regs.rs", sb.toString());
        return files;
    }

    static String ruIntType(int width) {
        if (width <= 8) {
            return "u8";
        }
        if (width <= 16) {
            return "u16";
        }
        return "u32";
    }

    private void emitRegister(StringBuilder sb, String chip, RegisterDef reg, String hash) {
        String rn = CGenerator.sanitize(reg.name()).toUpperCase();
        String fn = CGenerator.sanitize(reg.name()).toLowerCase();
        boolean wide = reg.width() > 32;
        String uType = wide ? "u64" : ruIntType(reg.width());
        sb.append("    // ---- ").append(reg.name()).append(" @ ")
                .append(CGenerator.hex(reg.address())).append(" width=").append(reg.width());
        if (reg.isAlias()) {
            sb.append(" alias_of=").append(reg.aliasOf());
        }
        if (reg.lockedBy() != null) {
            sb.append(" locked_by=").append(reg.lockedBy());
        }
        sb.append(" (model ").append(hash).append(") ----\n");
        sb.append("    pub const ").append(rn).append("_OFFSET: usize = ")
                .append(CGenerator.hex(reg.address())).append("; // model ").append(hash).append('\n');
        sb.append("    pub const ").append(rn).append("_RESET: ").append(uType)
                .append(" = ").append(CGenerator.hexWidth(reg.reset(), reg.width()))
                .append(";\n");
        sb.append("    pub const ").append(rn).append("_WIDTH: u32 = ").append(reg.width())
                .append(";\n");
        for (FieldDef field : reg.fields()) {
            String fp = rn + "_" + CGenerator.sanitize(field.name()).toUpperCase();
            sb.append("    // ").append(reg.name()).append('.').append(field.name())
                    .append(" [").append(field.msb()).append(':').append(field.lsb())
                    .append("] ").append(field.access()).append(" (model ").append(hash).append(")\n");
            sb.append("    pub const ").append(fp).append("_MASK: ").append(uType)
                    .append(" = ").append(CGenerator.hexWidth(field.mask(), reg.width()))
                    .append(";\n");
            sb.append("    pub const ").append(fp).append("_SHIFT: u32 = ").append(field.lsb())
                    .append(";\n");
        }
        sb.append('\n');

        sb.append("    /// 读取 ").append(reg.name());
        if (wide) {
            sb.append("（").append(reg.wordOrder() == RegisterDef.WordOrder.HIGH_FIRST
                    ? "高字优先" : "低字优先").append("）");
        }
        sb.append(" — model ").append(hash).append('\n');
        sb.append("    #[inline]\n");
        sb.append("    pub unsafe fn ").append(fn).append("_read(base: *const u8) -> ")
                .append(uType).append(" {\n");
        if (!wide) {
            sb.append("        core::ptr::read_volatile(base.add(").append(rn)
                    .append("_OFFSET) as *const ").append(uType).append(")\n");
        } else {
            sb.append("        let p = base.add(").append(rn).append("_OFFSET);\n");
            if (reg.wordOrder() == RegisterDef.WordOrder.LOW_FIRST) {
                sb.append("        let lo = core::ptr::read_volatile(p as *const u32) as u64;\n");
                sb.append("        let hi = core::ptr::read_volatile(p.add(4) as *const u32) as u64;\n");
            } else {
                sb.append("        let hi = core::ptr::read_volatile(p as *const u32) as u64;\n");
                sb.append("        let lo = core::ptr::read_volatile(p.add(4) as *const u32) as u64;\n");
            }
            sb.append("        (hi << 32) | lo\n");
        }
        sb.append("    }\n\n");

        sb.append("    /// 写入 ").append(reg.name()).append(" — model ").append(hash).append('\n');
        sb.append("    #[inline]\n");
        sb.append("    pub unsafe fn ").append(fn).append("_write(base: *mut u8, value: ")
                .append(uType).append(") {\n");
        if (!wide) {
            sb.append("        core::ptr::write_volatile(base.add(").append(rn)
                    .append("_OFFSET) as *mut ").append(uType).append(", value);\n");
        } else {
            sb.append("        let p = base.add(").append(rn).append("_OFFSET);\n");
            if (reg.wordOrder() == RegisterDef.WordOrder.LOW_FIRST) {
                sb.append("        core::ptr::write_volatile(p as *mut u32, value as u32);\n");
                sb.append("        core::ptr::write_volatile(p.add(4) as *mut u32, (value >> 32) as u32);\n");
            } else {
                sb.append("        core::ptr::write_volatile(p as *mut u32, (value >> 32) as u32);\n");
                sb.append("        core::ptr::write_volatile(p.add(4) as *mut u32, value as u32);\n");
            }
        }
        sb.append("    }\n\n");

        for (FieldDef field : reg.fields()) {
            emitFieldAccessors(sb, chip, reg, field, hash);
        }
    }

    private void emitFieldAccessors(StringBuilder sb, String chip, RegisterDef reg,
                                   FieldDef field, String hash) {
        String fp = CGenerator.sanitize(reg.name()).toUpperCase() + "_"
                + CGenerator.sanitize(field.name()).toUpperCase();
        String fn = CGenerator.sanitize(reg.name()).toLowerCase() + "_"
                + CGenerator.sanitize(field.name()).toLowerCase();
        String regFn = CGenerator.sanitize(reg.name()).toLowerCase();
        String uType = reg.width() > 32 ? "u64" : "u32";
        sb.append("    /// 读取移位后的 ").append(field.access()).append(" 字段 — model ")
                .append(hash).append('\n');
        sb.append("    #[inline]\n");
        sb.append("    pub fn ").append(fn).append("_get(regval: ").append(uType)
                .append(") -> ").append(uType).append(" {\n");
        sb.append("        (regval & ").append(fp).append("_MASK) >> ").append(fp)
                .append("_SHIFT\n    }\n\n");

        switch (field.access()) {
            case RW -> {
                sb.append("    /// 原子入口: RMW 置位 — model ").append(hash).append('\n');
                sb.append("    #[inline]\n");
                sb.append("    pub unsafe fn ").append(fn)
                        .append("_set(base: *mut u8) {\n");
                sb.append("        let v = ").append(regFn).append("_read(base as *const u8);\n");
                sb.append("        ").append(regFn).append("_write(base, v | ")
                        .append(fp).append("_MASK);\n    }\n\n");
                sb.append("    /// 原子入口: RMW 清零 — model ").append(hash).append('\n');
                sb.append("    #[inline]\n");
                sb.append("    pub unsafe fn ").append(fn)
                        .append("_clear(base: *mut u8) {\n");
                sb.append("        let v = ").append(regFn).append("_read(base as *const u8);\n");
                sb.append("        ").append(regFn).append("_write(base, v & !")
                        .append(fp).append("_MASK);\n    }\n\n");
            }
            case W1S -> {
                sb.append("    /// 原子入口: 写一置位（直接写掩码）— model ").append(hash).append('\n');
                sb.append("    #[inline]\n");
                sb.append("    pub unsafe fn ").append(fn)
                        .append("_set(base: *mut u8) {\n");
                sb.append("        ").append(regFn).append("_write(base, ").append(fp)
                        .append("_MASK as _);\n    }\n\n");
            }
            case W1C -> {
                sb.append("    /// 原子入口: 写一清零（直接写掩码）— model ").append(hash).append('\n');
                sb.append("    #[inline]\n");
                sb.append("    pub unsafe fn ").append(fn)
                        .append("_clear(base: *mut u8) {\n");
                sb.append("        ").append(regFn).append("_write(base, ").append(fp)
                        .append("_MASK as _);\n    }\n\n");
            }
            default -> {
            }
        }
    }
}
