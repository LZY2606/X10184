package com.regforge.gen;

import com.regforge.model.Field;
import com.regforge.model.Register;
import com.regforge.model.RegisterModel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Compares two model revisions and reports ABI changes (address/width/endian
 * and the generated entry points they affect) plus behavioral changes
 * (access semantics, reset, locks, effects).  Renames show up as
 * removed+added symbols with their impacted APIs rather than being silent.
 */
@Component
public class ModelDiffer {

    public List<DiffEntry> diff(RegisterModel oldM, RegisterModel newM) {
        List<DiffEntry> out = new ArrayList<>();

        if (oldM.getAddressBits() != newM.getAddressBits() || oldM.getWordBits() != newM.getWordBits()) {
            out.add(new DiffEntry("ABI", "CHANGED", "address_space",
                    "地址/字宽 " + oldM.getAddressBits() + "/" + oldM.getWordBits()
                            + " → " + newM.getAddressBits() + "/" + newM.getWordBits(),
                    allApis(newM)));
        }
        if (!oldM.getEndian().equalsIgnoreCase(newM.getEndian())) {
            out.add(new DiffEntry("ABI", "CHANGED", "endian",
                    "端序 " + oldM.getEndian() + " → " + newM.getEndian(),
                    multiWordApis(newM)));
        }

        Set<String> oldNames = new HashSet<>();
        Set<String> newNames = new HashSet<>();
        oldM.getRegisters().forEach(r -> oldNames.add(r.getName()));
        newM.getRegisters().forEach(r -> newNames.add(r.getName()));

        for (Register r : newM.getRegisters()) {
            if (!oldNames.contains(r.getName())) {
                out.add(new DiffEntry("ABI", "ADDED", r.getName(), "新增寄存器 " + r.getName(),
                        apisOf(r)));
                continue;
            }
            Register o = oldM.findRegister(r.getName());
            diffRegister(o, r, out);
        }
        for (Register r : oldM.getRegisters()) {
            if (!newNames.contains(r.getName())) {
                out.add(new DiffEntry("ABI", "REMOVED", r.getName(), "移除寄存器 " + r.getName(),
                        apisOf(r)));
            }
        }
        return out;
    }

    private void diffRegister(Register o, Register n, List<DiffEntry> out) {
        if (o.getAddress() != n.getAddress()) {
            out.add(new DiffEntry("ABI", "CHANGED", n.getName(),
                    "地址 0x" + Long.toUnsignedString(o.getAddress(), 16)
                            + " → 0x" + Long.toUnsignedString(n.getAddress(), 16),
                    apisOf(n)));
        }
        if (o.getWidthBits() != n.getWidthBits()) {
            out.add(new DiffEntry("ABI", "CHANGED", n.getName(),
                    "宽度 " + o.getWidthBits() + " → " + n.getWidthBits(),
                    apisOf(n)));
        }
        if (!o.getReadOrder().equalsIgnoreCase(n.getReadOrder())) {
            out.add(new DiffEntry("BEHAVIOR", "CHANGED", n.getName(),
                    "读取次序 " + o.getReadOrder() + " → " + n.getReadOrder(),
                    List.of(cSym(n, "read"), rustSym(n, "read"))));
        }
        if (!eq(o.getAliasOf(), n.getAliasOf())) {
            out.add(new DiffEntry("BEHAVIOR", "CHANGED", n.getName(),
                    "别名关系 " + o.getAliasOf() + " → " + n.getAliasOf(), apisOf(n)));
        }
        if (!eq(o.getLockedBy(), n.getLockedBy()) || !eq(o.getLockLevel(), n.getLockLevel())) {
            out.add(new DiffEntry("BEHAVIOR", "CHANGED", n.getName(),
                    "锁配置变化", List.of(cSym(n, "write"), rustSym(n, "write"))));
        }

        Set<String> of = new HashSet<>();
        Set<String> nf = new HashSet<>();
        o.getFields().forEach(f -> of.add(f.getName()));
        n.getFields().forEach(f -> nf.add(f.getName()));
        for (Field f : n.getFields()) {
            if (!of.contains(f.getName())) {
                out.add(new DiffEntry("ABI", "ADDED", n.getName() + "." + f.getName(),
                        "新增位域", List.of(cSym(n, "write"), rustSym(n, "write"))));
                continue;
            }
            Field fo = o.getFields().stream().filter(x -> x.getName().equals(f.getName())).findFirst().orElseThrow();
            if (fo.getLsb() != f.getLsb() || fo.getMsb() != f.getMsb()) {
                out.add(new DiffEntry("ABI", "CHANGED", n.getName() + "." + f.getName(),
                        "位位置 " + fo.getMsb() + ":" + fo.getLsb() + " → " + f.getMsb() + ":" + f.getLsb(),
                        List.of(cSym(n, "write"), rustSym(n, "read"))));
            }
            if (fo.mask() != f.mask()) {
                out.add(new DiffEntry("ABI", "CHANGED", n.getName() + "." + f.getName(),
                        "掩码变化", List.of(cSym(n, "write"), maskC(n, f), maskR(n, f))));
            }
            if (fo.getAccess() != f.getAccess()) {
                out.add(new DiffEntry("BEHAVIOR", "CHANGED", n.getName() + "." + f.getName(),
                        "访问语义 " + fo.getAccess() + " → " + f.getAccess(),
                        List.of(cSym(n, "write"), rustSym(n, "write"))));
            }
            if (fo.getReset() != f.getReset()) {
                out.add(new DiffEntry("BEHAVIOR", "CHANGED", n.getName() + "." + f.getName(),
                        "复位值变化", List.of()));
            }
            String oe = effects(fo);
            String ne = effects(f);
            if (!oe.equals(ne)) {
                out.add(new DiffEntry("BEHAVIOR", "CHANGED", n.getName() + "." + f.getName(),
                        "副作用链变化 `" + oe + "` → `" + ne + "`",
                        List.of(cSym(n, "write"), rustSym(n, "write"))));
            }
        }
        for (Field f : o.getFields()) {
            if (!nf.contains(f.getName())) {
                out.add(new DiffEntry("ABI", "REMOVED", n.getName() + "." + f.getName(),
                        "移除位域", List.of(cSym(n, "write"), maskC(n, f))));
            }
        }
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private String effects(Field f) {
        List<String> parts = new ArrayList<>();
        f.getEffects().stream().map(e -> e.normalizedAction() + ":" + e.target()).sorted().forEach(parts::add);
        return String.join(",", parts);
    }

    private List<String> apisOf(Register r) {
        return List.of(cSym(r, "read"), cSym(r, "read_consume"), cSym(r, "write"),
                rustSym(r, "read"), rustSym(r, "write"));
    }

    private List<String> allApis(RegisterModel m) {
        List<String> out = new ArrayList<>();
        for (Register r : m.getRegisters()) out.addAll(apisOf(r));
        return out;
    }

    private List<String> multiWordApis(RegisterModel m) {
        List<String> out = new ArrayList<>();
        for (Register r : m.getRegisters()) {
            if (r.isMultiWord(m.getWordBits())) {
                out.add(cSym(r, "read"));
                out.add(rustSym(r, "read"));
            }
        }
        return out;
    }

    private String cSym(Register r, String op) {
        return NameUtil.lower(r.getName()) + "_" + op + "() [C]";
    }

    private String rustSym(Register r, String op) {
        return NameUtil.lower(r.getName()) + "::" + op + "() [Rust]";
    }

    private String maskC(Register r, Field f) {
        return NameUtil.upper(r.getName()) + "_" + NameUtil.upper(f.getName()) + "_MASK [C]";
    }

    private String maskR(Register r, Field f) {
        return NameUtil.lower(r.getName()) + "::" + NameUtil.lower(f.getName()) + "::MASK [Rust]";
    }
}
