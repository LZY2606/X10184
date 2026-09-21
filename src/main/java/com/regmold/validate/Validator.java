package com.regmold.validate;

import com.regmold.domain.Access;
import com.regmold.domain.Effect;
import com.regmold.domain.Field;
import com.regmold.domain.LockRule;
import com.regmold.domain.Model;
import com.regmold.domain.ReadOrder;
import com.regmold.domain.Ref;
import com.regmold.domain.Register;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Semantic validation of a register model. */
public final class Validator {

    private Validator() {
    }

    public static List<ValidationIssue> validate(Model model) {
        List<ValidationIssue> issues = new ArrayList<>();
        Map<String, Register> byName = new HashMap<>();
        List<Register> nonAliases = new ArrayList<>();

        if (model.addressBits() <= 0 || model.addressBits() > 64) {
            issues.add(ValidationIssue.error("ADDR_BITS", "address_bits must be in 1..64"));
        }
        if (model.wordBytes() <= 0 || model.wordBytes() > 8 || (model.wordBytes() & (model.wordBytes() - 1)) != 0) {
            issues.add(ValidationIssue.error("WORD_BYTES", "word_bytes must be a power of two in 1..8"));
        }

        for (Register r : model.registers()) {
            if (byName.putIfAbsent(r.name(), r) != null) {
                issues.add(ValidationIssue.error("DUP_REG", "duplicate register name '" + r.name() + "'",
                        r.name()));
            }
        }

        for (Register r : model.registers()) {
            validateRegister(model, r, byName, issues);
            if (!r.isAlias()) {
                nonAliases.add(r);
            }
        }

        validateAddressRanges(nonAliases, model, issues);
        validateRefs(model, byName, issues);
        validateGraph(model, issues);
        return issues;
    }

    private static void validateRegister(Model model, Register r, Map<String, Register> byName,
                                         List<ValidationIssue> issues) {
        String path = r.name();
        if (r.widthBits() <= 0 || r.widthBits() % 8 != 0) {
            issues.add(ValidationIssue.error("REG_WIDTH", "register width must be a positive multiple of 8", path));
        }
        if (r.widthBits() > 64) {
            issues.add(ValidationIssue.error("REG_WIDTH", "register width above 64 bits is not supported", path));
        }
        if (r.address() < 0 || (model.addressBits() < 64 && Long.compareUnsigned(r.address(), (1L << model.addressBits()) - 1) > 0)) {
            issues.add(ValidationIssue.error("REG_ADDR", "address outside address space", path));
        }
        if (r.address() % model.wordBytes() != 0) {
            issues.add(ValidationIssue.error("REG_ALIGN", "address 0x" + Long.toUnsignedString(r.address(), 16)
                    + " is not aligned to word size " + model.wordBytes(), path));
        }

        boolean multiword = r.widthBits() > model.wordBits();
        if (multiword && r.readOrder() == null && !r.isAlias()) {
            issues.add(ValidationIssue.error("MULTIWORD_ORDER",
                    "multi-word register '" + r.name() + "' must declare read_order", path));
        }
        if (multiword && r.readOrder() != null && r.widthBits() != model.wordBits() * 2) {
            issues.add(ValidationIssue.error("MULTIWORD_SIZE",
                    "multi-word registers must be exactly two bus words", path));
        }

        if (r.isAlias()) {
            Register target = byName.get(r.aliasOf());
            if (target == null) {
                issues.add(ValidationIssue.error("ALIAS_TARGET",
                        "alias '" + r.name() + "' points at unknown register '" + r.aliasOf() + "'", path));
            } else if (target.address() != r.address()) {
                issues.add(ValidationIssue.error("ALIAS_ADDR",
                        "alias '" + r.name() + "' must share the address of '" + target.name()
                                + "' (0x" + Long.toUnsignedString(target.address(), 16) + ")", path));
            }
        }

        long covered = 0L;
        Set<String> fieldNames = new HashSet<>();
        long fieldReset = 0L;
        for (Field f : r.fields()) {
            if (!fieldNames.add(f.name())) {
                issues.add(ValidationIssue.error("DUP_FIELD",
                        "duplicate field name '" + f.name() + "'", path + "." + f.name()));
            }
            if (f.msb() >= r.widthBits()) {
                issues.add(ValidationIssue.error("FIELD_RANGE",
                        "field '" + f.name() + "' exceeds register width ("
                                + f.msb() + " >= " + r.widthBits() + ")", path + "." + f.name()));
            }
            if (r.widthBits() > model.wordBits()) {
                int wordBits = model.wordBits();
                int startWord = f.lsb() / wordBits;
                int endWord = f.msb() / wordBits;
                if (startWord != endWord) {
                    issues.add(ValidationIssue.warning("FIELD_CROSSES_WORD",
                            "field '" + f.name() + "' crosses the " + wordBits
                                    + "-bit bus word boundary (bits " + f.msb() + ".." + f.lsb()
                                    + "); software must respect the declared read/write order",
                            path + "." + f.name()));
                }
            }
            long mask = f.mask();
            if ((covered & mask) != 0L) {
                issues.add(ValidationIssue.error("FIELD_OVERLAP",
                        "field '" + f.name() + "' overlaps another field in '" + r.name() + "'",
                        path + "." + f.name()));
            }
            covered |= mask;
            if (f.width() < 64 && Long.compareUnsigned(f.reset(), (1L << f.width()) - 1L) > 0) {
                issues.add(ValidationIssue.error("FIELD_RESET",
                        "field '" + f.name() + "' reset value does not fit in " + f.width() + " bits",
                        path + "." + f.name()));
            }
            if (f.access() == Access.W1C || f.access() == Access.W1S) {
                if (f.reset() != 0L) {
                    issues.add(ValidationIssue.error("W1X_RESET",
                            "write-one field '" + f.name() + "' must have reset 0", path + "." + f.name()));
                }
            }
            fieldReset |= ((f.width() >= 64 ? f.reset() : (f.reset() << f.lsb())) & mask);
        }

        long holes = (~covered) & (r.widthBits() >= 64 ? ~0L : ((1L << r.widthBits()) - 1L));
        if (holes != 0L) {
            issues.add(new ValidationIssue(ValidationIssue.Severity.WARNING, "UNDECLARED_BITS",
                    "register '" + r.name() + "' leaves bits undeclared; they are treated as reserved: 0x"
                            + Long.toUnsignedString(holes, 16), path));
        }
        if (r.reset() != null) {
            long expected = r.widthBits() >= 64 ? fieldReset : fieldReset & ((1L << r.widthBits()) - 1L);
            if (r.reset() != expected) {
                issues.add(ValidationIssue.error("RESET_MISMATCH",
                        "register reset 0x" + Long.toUnsignedString(r.reset(), 16)
                                + " disagrees with field resets (0x" + Long.toUnsignedString(expected, 16) + ")",
                        path));
            }
        }
    }

    private static void validateAddressRanges(List<Register> nonAliases, Model model,
                                              List<ValidationIssue> issues) {
        List<Register> sorted = new ArrayList<>(nonAliases);
        sorted.sort(java.util.Comparator.comparingLong(Register::address));
        for (int i = 1; i < sorted.size(); i++) {
            Register prev = sorted.get(i - 1);
            Register cur = sorted.get(i);
            long prevEnd = prev.address() + prev.bytes() - 1;
            if (Long.compareUnsigned(prevEnd, cur.address()) >= 0) {
                issues.add(ValidationIssue.error("ADDR_OVERLAP",
                        "registers '" + prev.name() + "' and '" + cur.name()
                                + "' occupy overlapping address ranges", cur.name()));
            }
        }
    }

    private static void validateRefs(Model model, Map<String, Register> byName,
                                     List<ValidationIssue> issues) {
        for (Effect e : model.effects()) {
            checkRef(e.trigger(), byName, true, issues, "effect trigger");
            checkRef(e.target(), byName, false, issues, "effect target");
        }
        for (LockRule lock : model.locks()) {
            checkRef(lock.target(), byName, true, issues, "lock target");
            checkRef(lock.master(), byName, false, issues, "lock master");
        }
    }

    private static void checkRef(Ref ref, Map<String, Register> byName, boolean writable,
                                 List<ValidationIssue> issues, String what) {
        Register r = byName.get(ref.register());
        if (r == null) {
            issues.add(ValidationIssue.error("UNKNOWN_REF",
                    what + " references unknown register '" + ref.register() + "'"));
            return;
        }
        if (ref.isField() && r.field(ref.field()) == null) {
            issues.add(ValidationIssue.error("UNKNOWN_REF",
                    what + " references unknown field '" + ref.canonical() + "'"));
            return;
        }
        if (writable) {
            Access a = ref.isField() ? r.field(ref.field()).access() : r.access();
            if (a == Access.RO || a == Access.RC || a == Access.RSVD) {
                issues.add(ValidationIssue.warning("WRITE_TO_READONLY",
                        what + " '" + ref.canonical() + "' has access " + a.key()
                                + " and can never be written"));
            }
        }
    }

    private static void validateGraph(Model model, List<ValidationIssue> issues) {
        ConflictFinder.Result result = new ConflictFinder(model.effects()).analyze();
        for (ConflictFinder.ConflictPath conflict : result.conflicts()) {
            issues.add(ValidationIssue.error("SIDE_EFFECT_CONFLICT",
                    "contradictory side-effect chain: " + conflict.render()));
        }
        for (ConflictFinder.Path cycle : result.cycles()) {
            issues.add(ValidationIssue.error("SIDE_EFFECT_CYCLE",
                    "cyclic side-effect chain: " + cycle.render()));
        }
    }
}
