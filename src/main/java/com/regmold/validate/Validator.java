package com.regmold.validate;

import com.regmold.domain.Access;
import com.regmold.domain.Diagnostic;
import com.regmold.domain.EffectAction;
import com.regmold.domain.EffectModel;
import com.regmold.domain.FieldModel;
import com.regmold.domain.Model;
import com.regmold.domain.ParsedModel;
import com.regmold.domain.RegisterModel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class Validator {

    public List<Diagnostic> validate(ParsedModel parsed) {
        Model model = parsed.model;
        ModelIndex index = new ModelIndex(model);
        List<Diagnostic> ds = parsed.diagnostics;
        if (!model.endianness.equals("little") && !model.endianness.equals("big")) {
            ds.add(Diagnostic.error("ENDIANNESS", "unsupported endianness: " + model.endianness));
        }
        if (model.dataWidth <= 0 || model.dataWidth > 64 || Integer.bitCount(model.dataWidth) != 1) {
            ds.add(Diagnostic.error("DATA_WIDTH", "data_width must be a power of two within 1..64"));
        }

        for (RegisterModel r : model.registers) {
            validateRegister(r, index, ds);
        }
        validateOffsetGroups(index, ds);
        validateEffects(index, ds);
        detectLocks(index, ds);
        EffectCycleDetector.Cycle cycle = EffectCycleDetector.findShortestCycle(index);
        if (cycle != null) {
            Diagnostic d = Diagnostic.error("SIDE_EFFECT_CYCLE",
                    "contradictory side-effect chain through effect '" + cycle.effect + "'");
            d.path = cycle.path;
            d.effect = cycle.effect;
            ds.add(d);
        }
        return ds;
    }

    private void validateRegister(RegisterModel r, ModelIndex index, List<Diagnostic> ds) {
        int width = index.width(r);
        long full = index.fullMask(width);
        Set<Integer> used = new HashSet<>();
        Set<String> names = new HashSet<>();
        long reservedCoverage = 0L;

        if (r.aliasOf != null) {
            RegisterModel base = index.byName.get(r.aliasOf);
            if (base == null) {
                ds.add(Diagnostic.error("ALIAS_TARGET", "alias_of unknown register: " + r.aliasOf).at(r.name, null));
            } else {
                if (r.offset != base.offset) {
                    ds.add(Diagnostic.error("ALIAS_OFFSET",
                            "alias " + r.name + " must share offset with " + r.aliasOf).at(r.name, null));
                }
                if (r.aliasWrite == null) {
                    ds.add(Diagnostic.error("ALIAS_WRITE",
                            "alias " + r.name + " must declare alias_write semantics").at(r.name, null));
                } else if (!Set.of("set", "clear", "normal").contains(r.aliasWrite)) {
                    ds.add(Diagnostic.error("ALIAS_WRITE",
                            "alias_write must be one of set|clear|normal: " + r.aliasWrite).at(r.name, null));
                }
                if ("normal".equals(r.aliasWrite) && r.fields.isEmpty() == base.fields.isEmpty()) {
                    ds.add(Diagnostic.error("ALIAS_SEMANTICS",
                            "alias " + r.name + " at same address must have distinct write semantics from "
                                    + r.aliasOf).at(r.name, null));
                }
            }
        }

        if (r.offset % (modelDataWidth(index) / 8) != 0) {
            ds.add(Diagnostic.warning("ALIGNMENT",
                    "register " + r.name + " is not aligned to the bus width").at(r.name, null));
        }

        for (FieldModel f : r.fields) {
            if (!names.add(f.name)) {
                ds.add(Diagnostic.error("FIELD_DUPLICATE", "duplicate field name " + f.name).at(r.name, f.name));
            }
            if (f.bitStart < 0) {
                ds.add(Diagnostic.error("BIT_RANGE", "field " + f.name + " starts below bit 0").at(r.name, f.name));
            }
            if (f.bitEnd() >= width) {
                ds.add(Diagnostic.error("BIT_RANGE",
                        "field " + f.name + " spans [" + f.bitStart + ":" + f.bitEnd()
                                + "] beyond register width " + width).at(r.name, f.name));
            }
            for (int b = f.bitStart; b <= f.bitEnd() && b < width; b++) {
                if (!used.add(b)) {
                    ds.add(Diagnostic.error("FIELD_OVERLAP", "field " + f.name + " overlaps another field at bit " + b)
                            .at(r.name, f.name));
                }
            }
            if ((f.reset & ~f.linearMask()) != 0) {
                ds.add(Diagnostic.error("RESET_RANGE",
                        "reset value 0x" + Long.toUnsignedString(f.reset, 16) + " exceeds field " + f.name)
                        .at(r.name, f.name));
            }
            if (f.access == Access.RESERVED) {
                reservedCoverage |= f.linearMask();
            }
            if (f.latchFrom != null) {
                String[] parts = f.latchFrom.split("\\.");
                if (parts.length != 2 || index.field(parts[0], parts[1]) == null) {
                    ds.add(Diagnostic.error("LATCH_SOURCE",
                            "latch_from '" + f.latchFrom + "' does not resolve to a field").at(r.name, f.name));
                }
            }
        }

        long fieldReset = 0L;
        for (FieldModel f : r.fields) {
            fieldReset |= (f.reset & f.linearMask());
        }
        long implied = r.reset == null ? fieldReset : r.reset;
        if (r.reset != null && (r.reset & ~full) != 0) {
            ds.add(Diagnostic.error("RESET_RANGE",
                    "register reset 0x" + Long.toUnsignedString(r.reset, 16) + " exceeds register width")
                    .at(r.name, null));
        }
        if ((implied & reservedCoverage) != 0 && r.reset == null) {
            ds.add(Diagnostic.error("RESERVED_RESET",
                    "reserved bits of " + r.name + " must reset to zero").at(r.name, null));
        }

        boolean hasCrossWord = r.fields.stream().anyMatch(f -> f.bitEnd() >= index.model.dataWidth);
        if (hasCrossWord) {
            int dw = index.model.dataWidth;
            if (width <= dw || width % dw != 0) {
                ds.add(Diagnostic.error("MULTIWORD_WIDTH",
                        "register " + r.name + " with cross-word fields must have a width that is a multiple of "
                                + dw).at(r.name, null));
            }
            for (FieldModel cf : r.fields) {
                if (cf.bitStart / dw != cf.bitEnd() / dw) {
                    ds.add(Diagnostic.warning("CROSSWORD_FIELD",
                            "field " + cf.name + " spans a word boundary; generated accessors document the "
                                    + ("big".equals(r.endianness != null ? r.endianness : index.model.endianness)
                                            ? "high-first" : "low-first") + " word sequence")
                            .at(r.name, cf.name));
                }
            }
            if (r.aliasOf != null) {
                ds.add(Diagnostic.error("MULTIWORD_ALIAS",
                        "multi-word register " + r.name + " cannot be aliased").at(r.name, null));
            }
        }
        if (r.lock != null && index.byName.get(r.lock.register) == null) {
            ds.add(Diagnostic.error("LOCK_TARGET",
                    "lock register " + r.lock.register + " does not exist").at(r.name, null));
        }
    }

    private int modelDataWidth(ModelIndex index) {
        return index.model.dataWidth;
    }

    private void validateOffsetGroups(ModelIndex index, List<Diagnostic> ds) {
        Map<Long, List<RegisterModel>> groups = index.byOffset;
        for (Map.Entry<Long, List<RegisterModel>> entry : groups.entrySet()) {
            List<RegisterModel> regs = entry.getValue();
            Set<String> names = new HashSet<>();
            int normals = 0;
            for (RegisterModel r : regs) {
                if (!names.add(r.name)) {
                    ds.add(Diagnostic.error("DUPLICATE_NAME", "duplicate register name: " + r.name));
                }
                if (r.aliasOf == null) {
                    normals++;
                }
            }
            if (regs.size() > 1 && normals != 1) {
                ds.add(Diagnostic.error("ALIAS_GROUP",
                        "address 0x" + Long.toUnsignedString(entry.getKey(), 16)
                                + " must contain exactly one base register"));
            }
            if (regs.size() == 1 && regs.get(0).aliasOf != null) {
                ds.add(Diagnostic.error("ALIAS_GROUP",
                        "alias " + regs.get(0).name + " has no base register at its address"));
            }
        }
    }

    private void validateEffects(ModelIndex index, List<Diagnostic> ds) {
        Set<String> seen = new HashSet<>();
        for (EffectModel e : index.model.effects) {
            if (!seen.add(e.name)) {
                ds.add(Diagnostic.error("EFFECT_DUPLICATE", "duplicate effect name: " + e.name));
            }
            RegisterModel trigger = index.byName.get(e.triggerRegister);
            if (trigger == null) {
                ds.add(Diagnostic.error("EFFECT_TRIGGER", "trigger register unknown: " + e.triggerRegister));
                continue;
            }
            RegisterModel target = index.byName.get(e.targetRegister);
            if (target == null) {
                ds.add(Diagnostic.error("EFFECT_TARGET", "target register unknown: " + e.targetRegister));
                continue;
            }
            if (e.triggerMask == 0) {
                ds.add(Diagnostic.warning("EFFECT_MASK", "effect " + e.name + " has an empty trigger mask"));
            }
            if ((e.triggerMask & ~index.fullMask(index.width(trigger))) != 0) {
                ds.add(Diagnostic.error("EFFECT_MASK",
                        "trigger mask of " + e.name + " exceeds width of " + e.triggerRegister));
            }
            FieldModel tf = index.field(e.targetRegister, e.targetField);
            if (e.targetField != null && tf == null) {
                ds.add(Diagnostic.error("EFFECT_TARGET",
                        "target field unknown: " + e.targetRegister + "." + e.targetField));
            }
            if (e.action == EffectAction.LATCH) {
                if (e.sourceRegister == null) {
                    ds.add(Diagnostic.error("EFFECT_SOURCE",
                            "latch effect " + e.name + " requires a source"));
                } else if (index.byName.get(e.sourceRegister) == null) {
                    ds.add(Diagnostic.error("EFFECT_SOURCE",
                            "source register unknown: " + e.sourceRegister));
                } else if (e.sourceField != null && index.field(e.sourceRegister, e.sourceField) == null) {
                    ds.add(Diagnostic.error("EFFECT_SOURCE",
                            "source field unknown: " + e.sourceRegister + "." + e.sourceField));
                }
                if (tf == null) {
                    ds.add(Diagnostic.error("EFFECT_TARGET",
                            "latch effect " + e.name + " must target a field"));
                }
            }
            if (tf != null && tf.access == Access.RO && e.action != EffectAction.LATCH) {
                ds.add(Diagnostic.warning("EFFECT_TARGET",
                        "effect " + e.name + " mutates read-only field " + e.targetField));
            }
        }
    }

    private void detectLocks(ModelIndex index, List<Diagnostic> ds) {
        for (RegisterModel r : index.model.registers) {
            if (r.lock == null) {
                continue;
            }
            RegisterModel lockReg = index.byName.get(r.lock.register);
            int lockWidth = index.width(lockReg);
            if (r.lock.bit < 0 || r.lock.bit >= lockWidth) {
                ds.add(Diagnostic.error("LOCK_BIT",
                        "lock bit " + r.lock.bit + " out of range in " + r.lock.register).at(r.name, null));
            }
        }
    }
}
