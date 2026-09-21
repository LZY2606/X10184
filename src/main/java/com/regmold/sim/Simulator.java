package com.regmold.sim;

import com.regmold.model.AccessType;
import com.regmold.model.Field;
import com.regmold.model.ModelException;
import com.regmold.model.Register;
import com.regmold.model.RegisterModel;
import com.regmold.model.SideEffect;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Step-by-step register state machine. Aliases share storage by address;
 * atomic set/clear registers modify their target's storage.
 */
public class Simulator {

    public record ReadResult(long value, List<Long> words, List<String> notes) {}

    public record WriteResult(boolean accepted, long newValue,
                              List<String> violations, List<String> effects) {}

    private final RegisterModel model;
    private final Map<Long, Long> state = new LinkedHashMap<>();

    public Simulator(RegisterModel model) {
        this.model = model;
        reset();
    }

    public final void reset() {
        state.clear();
        for (Register r : model.registers()) {
            if (!r.isAlias()) {
                state.put(r.address(), r.reset() & r.mask());
            }
        }
    }

    /** Debug preview: returns the value without consuming read-clear fields. */
    public long peek(String regName) {
        Register reg = requireReg(regName);
        return state.get(storageAddress(reg)) & reg.mask();
    }

    public ReadResult read(String regName) {
        Register reg = requireReg(regName);
        long cur = state.get(storageAddress(reg)) & reg.mask();
        List<String> notes = new ArrayList<>();
        List<Long> words = new ArrayList<>();
        if (reg.width() > model.wordWidth()) {
            int count = reg.width() / model.wordWidth();
            List<Long> ordered = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                ordered.add((cur >>> (i * model.wordWidth())) & wordMask());
            }
            if (reg.readOrder() == Register.ReadOrder.HIGH_FIRST) {
                ordered = ordered.reversed();
            }
            words.addAll(ordered);
            notes.add("multi-word read, " + (reg.readOrder() == Register.ReadOrder.LOW_FIRST
                    ? "low word first" : "high word first"));
            if (reg.latchOnRead()) {
                notes.add("remaining words latched at first read");
            }
        }
        long clearMask = 0;
        StringBuilder consumed = new StringBuilder();
        for (Field f : reg.fields()) {
            if (f.access() == AccessType.RC) {
                clearMask |= f.mask();
                if (consumed.length() > 0) {
                    consumed.append(", ");
                }
                consumed.append(f.name());
            }
        }
        if (clearMask != 0) {
            long addr = storageAddress(reg);
            state.put(addr, state.get(addr) & ~clearMask);
            notes.add("read-clear consumed: " + consumed);
        }
        return new ReadResult(cur, words, notes);
    }

    public WriteResult write(String regName, long value) {
        Register reg = requireReg(regName);
        List<String> violations = new ArrayList<>();
        List<String> effects = new ArrayList<>();
        value &= reg.mask();

        if (reg.lockedBy() != null) {
            String[] parts = reg.lockedBy().split("\\.");
            Register lockReg = model.reg(parts[0]);
            Field lockField = lockReg.field(parts[1]);
            long lockVal = (state.get(storageAddress(lockReg)) & lockField.mask()) >>> lockField.offset();
            if (lockVal != 0) {
                violations.add("write rejected: locked by " + reg.lockedBy());
                return new WriteResult(false, state.get(storageAddress(reg)) & reg.mask(),
                        violations, effects);
            }
        }

        long addr = storageAddress(reg);
        long cur = state.get(addr);
        long next;
        Register semantics = reg.isAlias() && reg.atomicOp() == Register.AtomicOp.NONE
                && reg.fields().isEmpty() ? model.reg(reg.aliasOf()) : reg;
        if (reg.atomicOp() == Register.AtomicOp.SET) {
            next = cur | value;
            effects.add("atomic set via " + reg.name());
        } else if (reg.atomicOp() == Register.AtomicOp.CLEAR) {
            next = cur & ~value;
            effects.add("atomic clear via " + reg.name());
        } else {
            next = cur;
            long covered = 0;
            for (Field f : semantics.fields()) {
                long fmask = f.mask();
                covered |= fmask;
                long vbits = value & fmask;
                switch (f.access()) {
                    case RW, RC, WO -> next = (next & ~fmask) | vbits;
                    case RO -> { /* writes to read-only bits are ignored */ }
                    case W1C -> next = next & ~vbits;
                    case W1S -> next = next | vbits;
                    case RESERVED -> {
                        if (vbits != (cur & fmask)) {
                            violations.add("reserved field " + f.name()
                                    + " must be written back with its read value");
                        }
                    }
                }
            }
            long uncovered = semantics.mask() & ~covered;
            if ((value & uncovered) != (cur & uncovered)) {
                violations.add("write modifies reserved (undocumented) bits of " + reg.name());
            }
        }
        state.put(addr, next & semantics.mask());
        Register effectReg = reg.atomicOp() != Register.AtomicOp.NONE && reg.atomicTarget() != null
                ? model.reg(reg.atomicTarget()) : semantics;
        applySideEffects(effectReg.name(), effects, violations);
        return new WriteResult(violations.isEmpty(), state.get(addr) & reg.mask(), violations, effects);
    }

    private void applySideEffects(String startReg, List<String> effects, List<String> violations) {
        Deque<String> queue = new ArrayDeque<>();
        queue.add(startReg);
        List<String> fired = new ArrayList<>();
        Map<String, Long> assigned = new LinkedHashMap<>();
        int steps = 0;
        while (!queue.isEmpty()) {
            String regName = queue.poll();
            for (SideEffect e : model.sideEffects()) {
                if (!e.triggerReg().equals(regName)) {
                    continue;
                }
                Register trigReg = model.reg(e.triggerReg());
                Field trigField = trigReg.field(e.triggerField());
                long trigVal = (state.get(storageAddress(trigReg)) & trigField.mask()) >>> trigField.offset();
                if (trigVal != e.triggerValue()) {
                    continue;
                }
                if (fired.contains(e.describe())) {
                    violations.add("cyclic side effect re-triggered and stopped: " + e.describe());
                    continue;
                }
                fired.add(e.describe());
                String targetKey = e.targetReg() + "." + e.targetField();
                if (assigned.containsKey(targetKey) && assigned.get(targetKey) != e.targetValue()) {
                    violations.add("contradictory side effects on " + targetKey + ": "
                            + assigned.get(targetKey) + " vs " + e.targetValue());
                }
                assigned.put(targetKey, e.targetValue());
                Register tgtReg = model.reg(e.targetReg());
                Field tgtField = tgtReg.field(e.targetField());
                long taddr = storageAddress(tgtReg);
                long tcur = state.get(taddr);
                long tnew = (tcur & ~tgtField.mask())
                        | ((e.targetValue() << tgtField.offset()) & tgtField.mask());
                state.put(taddr, tnew);
                effects.add(e.describe());
                queue.add(e.targetReg());
                if (++steps > 256) {
                    violations.add("side-effect chain did not settle (cycle)");
                    return;
                }
            }
        }
    }

    /** Snapshot of architectural state keyed by register name (aliases included). */
    public Map<String, String> snapshot() {
        Map<String, String> out = new LinkedHashMap<>();
        for (Register r : model.registers()) {
            out.put(r.name(), "0x" + Long.toHexString(state.get(storageAddress(r)) & r.mask()));
        }
        return out;
    }

    private long storageAddress(Register reg) {
        if (reg.atomicOp() != Register.AtomicOp.NONE && reg.atomicTarget() != null) {
            return model.reg(reg.atomicTarget()).address();
        }
        return reg.address();
    }

    private long wordMask() {
        return model.wordWidth() >= 64 ? -1L : (1L << model.wordWidth()) - 1L;
    }

    private Register requireReg(String name) {
        Register r = model.reg(name);
        if (r == null) {
            throw new ModelException("unknown register: " + name);
        }
        return r;
    }
}
