package com.regmold.sim;

import com.regmold.model.*;

import java.util.*;

/**
 * Step-by-step register state machine.
 * State is keyed by the base register name (aliases share their base's state).
 */
public class Simulator {

    private final ChipModel model;
    private final Map<String, Long> state = new LinkedHashMap<>();

    public Simulator(ChipModel model) {
        this.model = model;
        reset();
    }

    public final void reset() {
        state.clear();
        for (RegisterDef r : model.registers()) {
            if (r.isAlias()) continue;
            long v = r.reset();
            for (BitField f : r.fields()) {
                if (f.reset() != null) {
                    v = (v & ~f.mask()) | ((f.reset() << f.offset()) & f.mask());
                }
            }
            state.put(r.name(), v);
        }
    }

    public Map<String, Long> snapshot() {
        return Collections.unmodifiableMap(state);
    }

    public List<SimStep> run(List<SimOp> ops) {
        List<SimStep> steps = new ArrayList<>();
        int i = 0;
        for (SimOp op : ops) {
            List<String> events = new ArrayList<>();
            String valueStr = "";
            switch (op.op()) {
                case "read" -> valueStr = doRead(op.register(), false, events);
                case "preview" -> valueStr = doRead(op.register(), true, events);
                case "write" -> doWrite(op.register(), op.value() == null ? 0 : op.value(), events);
                default -> events.add("unknown op: " + op.op());
            }
            Map<String, String> after = new LinkedHashMap<>();
            state.forEach((k, v) -> after.put(k, hex(v)));
            steps.add(new SimStep(i++, op.op(), op.register(), valueStr, List.copyOf(events), after));
        }
        return steps;
    }

    private String hex(long v) {
        return "0x" + Long.toHexString(v);
    }

    /** Read a register; multi-word registers report the ordered word sequence. */
    private String doRead(String regName, boolean preview, List<String> events) {
        RegisterDef r = model.register(regName);
        if (r == null) { events.add("unknown register " + regName); return ""; }
        RegisterDef base = model.resolveBase(r);
        long cur = state.get(base.name());
        if (r.words() > 1) {
            StringBuilder sb = new StringBuilder();
            int[] order = wordOrder(r);
            for (int w : order) {
                long word = (cur >>> (w * 32)) & 0xFFFF_FFFFL;
                sb.append(String.format("word%d=0x%08x ", w, word));
            }
            events.add((preview ? "preview " : "read ") + r.name() + " (" + r.wordOrder() + "): " + sb.toString().trim());
        } else {
            events.add((preview ? "preview " : "read ") + r.name() + " = " + hex(cur));
        }
        if (!preview) {
            long cleared = cur;
            for (BitField f : r.fields()) {
                if (f.access() == AccessType.RC && (cur & f.mask()) != 0) {
                    cleared &= ~f.mask();
                    events.add("read-clear consumed " + r.name() + "." + f.name());
                }
            }
            if (cleared != cur) state.put(base.name(), cleared);
        } else {
            events.add("debug preview: no state consumed");
        }
        return hex(cur);
    }

    private int[] wordOrder(RegisterDef r) {
        int n = r.words();
        int[] order = new int[n];
        for (int i = 0; i < n; i++) order[i] = "hi-first".equals(r.wordOrder()) ? n - 1 - i : i;
        return order;
    }

    private void doWrite(String regName, long value, List<String> events) {
        RegisterDef r = model.register(regName);
        if (r == null) { events.add("unknown register " + regName); return; }
        RegisterDef base = model.resolveBase(r);
        // lock check (lock declared on the written register or its base)
        LockSpec lock = r.lockedBy() != null ? r.lockedBy() : base.lockedBy();
        if (lock != null) {
            RegisterDef lockReg = model.register(lock.register());
            if (lockReg != null) {
                RegisterDef lockBase = model.resolveBase(lockReg);
                BitField lf = lockReg.field(lock.field());
                long lockVal = (state.get(lockBase.name()) & lf.mask()) >>> lf.offset();
                if (lockVal != lock.openValue()) {
                    events.add("write to " + r.name() + " BLOCKED by lock " + lock.register() + "." + lock.field()
                            + " (current " + lockVal + ", needs " + lock.openValue() + ")");
                    return;
                }
                events.add("lock " + lock.register() + "." + lock.field() + " open, write allowed");
            }
        }
        long cur = state.get(base.name());
        long next;
        if (r.isAlias() && r.semantic() != null) {
            next = applyAliasSemantic(r, base, cur, value, events);
        } else {
            next = applyFields(r, cur, value, events);
        }
        state.put(base.name(), next);
        events.add(r.name() + ": " + hex(cur) + " -> " + hex(next));
        cascadeSideEffects(r, cur, next, events);
    }

    private long applyAliasSemantic(RegisterDef alias, RegisterDef base, long cur, long value, List<String> events) {
        long writableMask = 0;
        for (BitField f : base.fields()) {
            if (f.access() != AccessType.RESERVED && f.access() != AccessType.RO) writableMask |= f.mask();
        }
        long v = value & writableMask;
        return switch (alias.semantic().toLowerCase()) {
            case "w1s" -> { events.add("alias " + alias.name() + " atomic set of " + hex(v)); yield cur | v; }
            case "w1c" -> { events.add("alias " + alias.name() + " atomic clear of " + hex(v)); yield cur & ~v; }
            default -> (cur & ~writableMask) | v;
        };
    }

    private long applyFields(RegisterDef r, long cur, long value, List<String> events) {
        long next = cur;
        for (BitField f : r.fields()) {
            long mask = f.mask();
            long fv = value & mask;
            switch (f.access()) {
                case RW, WO -> next = (next & ~mask) | fv;
                case RO -> {
                    if (fv != (cur & mask)) events.add("write to read-only " + r.name() + "." + f.name() + " ignored");
                }
                case W1C -> next = next & ~fv;
                case W1S -> next = next | fv;
                case RC -> { /* read-clear fields ignore writes */ }
                case RESERVED -> {
                    if (fv != (cur & mask)) {
                        events.add("reserved " + r.name() + "." + f.name()
                                + " write-back preserved at read value " + hex(cur & mask));
                    }
                }
            }
        }
        return next;
    }

    private record Effect(RegisterDef reg, BitField field, SideEffect se) {}

    /** Cascade side effects of fields whose stored value changed. Bounded to expose cycles. */
    private void cascadeSideEffects(RegisterDef r, long before, long after, List<String> events) {
        ArrayDeque<Effect> queue = new ArrayDeque<>();
        collectTriggered(r, before, after, queue);
        int guard = 0;
        while (!queue.isEmpty()) {
            if (++guard > 256) {
                events.add("side-effect cascade did not settle after 256 applications (cycle in model)");
                return;
            }
            Effect e = queue.poll();
            RegisterDef targetBase = model.resolveBase(e.reg());
            long tcur = state.get(targetBase.name());
            long mask = e.field().mask();
            long tnext = switch (e.se().action()) {
                case SET -> tcur | mask;
                case CLEAR -> tcur & ~mask;
                case TOGGLE -> tcur ^ mask;
            };
            if (tnext != tcur) {
                state.put(targetBase.name(), tnext);
                events.add("side-effect " + e.se().action() + " " + e.reg().name() + "." + e.field().name()
                        + ": " + hex(tcur) + " -> " + hex(tnext));
                collectTriggered(e.reg(), tcur, tnext, queue);
            }
        }
    }

    private void collectTriggered(RegisterDef r, long before, long after, Collection<Effect> queue) {
        for (BitField f : r.fields()) {
            if (f.sideEffects().isEmpty()) continue;
            if ((before & f.mask()) != (after & f.mask())) {
                for (SideEffect se : f.sideEffects()) {
                    RegisterDef tr = model.register(se.targetRegister());
                    BitField tf = tr == null ? null : tr.field(se.targetField());
                    if (tf != null) queue.add(new Effect(tr, tf, se));
                }
            }
        }
    }
}
