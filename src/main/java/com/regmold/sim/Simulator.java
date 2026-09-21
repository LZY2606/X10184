package com.regmold.sim;

import com.regmold.domain.Access;
import com.regmold.domain.Effect;
import com.regmold.domain.EffectAction;
import com.regmold.domain.Field;
import com.regmold.domain.LockRule;
import com.regmold.domain.Model;
import com.regmold.domain.ReadOrder;
import com.regmold.domain.Ref;
import com.regmold.domain.Register;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Executable state model for read/write previews. Backing storage is keyed by the
 *  address of the primary register, so aliases share one state image. */
public final class Simulator {

    private final Model model;
    private final Map<String, Long> backing = new HashMap<>();
    private final Map<String, Boolean> latchConsumed = new HashMap<>();
    private final Map<String, Long> latchedSnapshot = new HashMap<>();

    public Simulator(Model model) {
        this.model = model;
        reset();
    }

    public void reset() {
        backing.clear();
        latchConsumed.clear();
        latchedSnapshot.clear();
        for (Register r : model.registers()) {
            if (!r.isAlias()) {
                backing.put(r.name(), resetValue(r));
            }
        }
    }

    private long resetValue(Register r) {
        if (r.reset() != null) {
            return r.reset();
        }
        long v = 0L;
        for (Field f : r.fields()) {
            v |= (f.width() >= 64 ? f.reset() : (f.reset() << f.lsb())) & f.mask();
        }
        return v;
    }

    /** Current live value without latching and without consuming read-clear bits. */
    public long peek(String regName) {
        Register r = reg(regName);
        if (r == null) {
            throw new IllegalArgumentException("unknown register: " + regName);
        }
        return backing.get(primary(r).name());
    }

    public long peekField(String regName, String fieldName) {
        Register r = reg(regName);
        Field f = field(r, fieldName);
        return extract(peek(regName), f);
    }

    public Map<String, Long> snapshot() {
        Map<String, Long> out = new LinkedHashMap<>();
        for (Register r : model.registers()) {
            if (!r.isAlias()) {
                out.put(r.name(), backing.get(r.name()));
            }
        }
        return out;
    }

    // ---- writes ---------------------------------------------------------------

    public SimResult write(String regName, long value) {
        Register r = reg(regName);
        if (r == null) {
            return SimResult.fail("unknown register: " + regName);
        }
        List<SimEvent> events = new ArrayList<>();
        long before = backing.get(primary(r).name());
        Register primary = primary(r);
        Map<Field, Access> effective = effectivePolicies(r, primary);
        long after = applyWrite(primary, before, value, effective, r, events);
        backing.put(primary.name(), after);
        latchConsumed.put(primary.name(), false);
        fireWrites(r, effective, value, before, after, events);
        return new SimResult(true, null, after, List.copyOf(events));
    }

    /** Atomic set/clear entry: read-modify-write performed as one bus operation;
     *  a lock on the target blocks the whole operation. */
    public SimResult atomicMask(String regName, long setMask, long clearMask) {
        Register r = reg(regName);
        if (r == null) {
            return SimResult.fail("unknown register: " + regName);
        }
        List<SimEvent> events = new ArrayList<>();
        Register primary = primary(r);
        if (isLocked(ref(primary), primary, backing.get(primary.name()))) {
            events.add(new SimEvent("locked", "atomic access blocked by lock register",
                    primary.name(), backing.get(primary.name()), backing.get(primary.name())));
            return new SimResult(true, null, backing.get(primary.name()), List.copyOf(events));
        }
        long before = backing.get(primary.name());
        long value = (before | setMask) & ~clearMask;
        Map<Field, Access> effective = effectivePolicies(primary, primary);
        long after = applyWrite(primary, before, value, effective, primary, events);
        backing.put(primary.name(), after);
        latchConsumed.put(primary.name(), false);
        events.add(0, new SimEvent("atomic", "atomic set/clear bus operation",
                primary.name(), before, after));
        fireWrites(primary, effective, value, before, after, events);
        return new SimResult(true, null, after, List.copyOf(events));
    }

    // ---- reads ----------------------------------------------------------------

    /** Bus read of one word index (0 = least significant word) of a primary register.
     *  Multi-word reads follow the declared order and latch; read-clear bits are consumed
     *  only on the first valid read of a pair. */
    public SimResult read(String regName, int wordIndex) {
        Register r = reg(regName);
        if (r == null) {
            return SimResult.fail("unknown register: " + regName);
        }
        Register primary = primary(r);
        List<SimEvent> events = new ArrayList<>();
        int words = primary.widthBits() / model.wordBits();
        if (wordIndex < 0 || wordIndex >= words) {
            return SimResult.fail("word index out of range");
        }
        long value;
        if (words == 1) {
            value = backing.get(primary.name());
            value = consumeReadClears(primary, value, events);
            backing.put(primary.name(), value);
        } else {
            value = readMultiword(primary, wordIndex, events);
        }
        long word = extractWord(value, wordIndex);
        return new SimResult(true, null, word, List.copyOf(events));
    }

    private long readMultiword(Register primary, int wordIndex, List<SimEvent> events) {
        int lowFirst = primary.readOrder() == ReadOrder.LOW_FIRST ? 0 : 1;
        int other = 1 - lowFirst;
        boolean consumed = latchConsumed.getOrDefault(primary.name(), false);
        long full = backing.get(primary.name());
        if (!consumed) {
            if (wordIndex != lowFirst) {
                events.add(new SimEvent("order", "read order violated for '" + primary.name()
                        + "': expected " + primary.readOrder().key() + "; returning undefined latch",
                        primary.name(), null, null));
            }
            long rcMask = readClearMask(primary);
            if (rcMask != 0L) {
                events.add(new SimEvent("rc", "read-clear bits consumed",
                        primary.name(), full, full & ~rcMask));
                full &= ~rcMask;
                backing.put(primary.name(), full);
            }
            latchedSnapshot.put(primary.name(), full);
            latchConsumed.put(primary.name(), true);
        }
        long snap = latchedSnapshot.getOrDefault(primary.name(), full);
        if (wordIndex == other) {
            latchConsumed.put(primary.name(), false);
        }
        return snap;
    }

    // ---- write semantics ------------------------------------------------------

    private long applyWrite(Register primary, long before, long written,
                            Map<Field, Access> policies, Register port,
                            List<SimEvent> events) {
        long after = before;
        List<Field> ordered = new ArrayList<>(policies.keySet());
        ordered.sort(java.util.Comparator.comparingInt(Field::lsb));
        for (Field f : ordered) {
            Access policy = policies.get(f);
            if (policy == null) {
                policy = Access.RSVD;
            }
            boolean locked = isLocked(Ref.field(primary.name(), f.name()), primary, before);
            if (locked) {
                events.add(new SimEvent("locked", "field '" + f.name() + "' write blocked",
                        primary.name() + "." + f.name(), extract(before, f), extract(before, f)));
                continue;
            }
            long wf = extract(written, f);
            long old = extract(after, f);
            long nv = old;
            switch (policy) {
                case RW -> nv = wf;
                case RO, RC -> {
                    events.add(new SimEvent("ignore", "read-only field '" + f.name() + "' write ignored",
                            primary.name() + "." + f.name(), old, old));
                }
                case W1C -> nv = old & ~wf;
                case W1S -> nv = old | wf;
                case RSVD -> nv = old;
            }
            after = setField(after, f, nv);
        }
        // undeclared bits are implicitly reserved
        long declared = 0L;
        for (Field f : primary.fields()) {
            declared |= f.mask();
        }
        long reservedUndeclared = before & ~declared;
        after = (after & declared) | reservedUndeclared;
        if (after != before) {
            events.add(new SimEvent("store", "register state updated via '" + port.name() + "'",
                    primary.name(), before, after));
        }
        return after;
    }

    private Map<Field, Access> effectivePolicies(Register port, Register primary) {
        Map<Integer, Field> byLsb = new HashMap<>();
        Map<Field, Access> map = new LinkedHashMap<>();
        for (Field f : primary.fields()) {
            byLsb.put(f.lsb(), f);
            map.put(f, f.access());
        }
        if (port != primary) {
            for (Field af : port.fields()) {
                Field base = byLsb.get(af.lsb());
                Field key = base != null ? base : af;
                map.put(key, af.access());
            }
            if (port.fields().isEmpty()) {
                // Policy-only alias port: whole register takes the port access policy.
                for (Field f : primary.fields()) {
                    map.put(f, port.access());
                }
            }
        }
        return map;
    }

    private void fireWrites(Register port, Map<Field, Access> policies, long written,
                            long before, long after, List<SimEvent> events) {
        Register primary = primary(port);
        // Effects are declared against primary register names; alias ports share semantics.
        java.util.List<String> portNames = new java.util.ArrayList<>();
        portNames.add(port.name());
        if (port.isAlias()) {
            portNames.add(primary.name());
        }
        // Register-level effects trigger on any bus write.
        for (Effect e : model.effects()) {
            if (!e.trigger().isField() && portNames.contains(e.trigger().register())) {
                applyEffect(e, after, events, new HashSet<>());
            }
        }
        // Field-level effects trigger when at least one bit of the field changed.
        for (Field f : primary.fields()) {
            if ((f.mask() & (before ^ after)) != 0L) {
                for (Effect e : model.effects()) {
                    if (e.trigger().isField() && portNames.contains(e.trigger().register())
                            && e.trigger().field().equals(f.name())) {
                        applyEffect(e, after, events, new HashSet<>());
                    }
                }
            }
        }
    }

    private void applyEffect(Effect e, long ctx, List<SimEvent> events, Set<String> seen) {
        String key = e.action() + "@" + e.target().canonical();
        if (!seen.add(key)) {
            return;
        }
        Register tr = reg(e.target().register());
        if (tr == null) {
            return;
        }
        Register primary = primary(tr);
        long before = backing.get(primary.name());
        long mask;
        String targetName;
        if (e.target().isField()) {
            Field f = tr.field(e.target().field());
            if (f == null) {
                return;
            }
            mask = f.mask();
            targetName = primary.name() + "." + f.name();
        } else {
            mask = primary.widthBits() >= 64 ? ~0L : (1L << primary.widthBits()) - 1L;
            targetName = primary.name();
        }
        long after = e.action() == EffectAction.SET ? before | mask : before & ~mask;
        if (after != before) {
            backing.put(primary.name(), after);
            events.add(new SimEvent("effect", e.action().name().toLowerCase() + " driven by '"
                    + e.trigger().canonical() + "'", targetName, before, after));
        }
        // Cascade: effects triggered by the state change of the target.
        for (Effect next : model.effects()) {
            if (next.trigger().register().equals(primary.name())
                    && (!next.trigger().isField() || (maskOf(primary, next.trigger().field()) & (before ^ after)) != 0L)
                    && !next.trigger().equals(e.trigger())) {
                applyEffect(next, after, events, seen);
            }
        }
    }

    private long maskOf(Register r, String fieldName) {
        Field f = r.field(fieldName);
        return f == null ? 0L : f.mask();
    }

    private boolean isLocked(Ref target, Register primary, long current) {
        for (LockRule lock : model.locks()) {
            if (!matches(lock.target(), target, primary)) {
                continue;
            }
            Register mr = reg(lock.master().register());
            if (mr == null) {
                continue;
            }
            long mv = backing.get(primary(mr).name());
            boolean active;
            if (lock.master().isField()) {
                Field mf = mr.field(lock.master().field());
                active = mf != null && extract(mv, mf) != 0L;
            } else {
                long mask = primary(mr).widthBits() >= 64 ? ~0L
                        : (1L << primary(mr).widthBits()) - 1L;
                active = (mv & mask) != 0L;
            }
            if (active) {
                return true;
            }
        }
        return false;
    }

    private boolean matches(Ref lockTarget, Ref writeTarget, Register writePrimary) {
        if (!lockTarget.register().equals(writeTarget.register())
                && !lockTarget.register().equals(writePrimary.name())) {
            return false;
        }
        if (!lockTarget.isField()) {
            return true;
        }
        return lockTarget.field().equals(writeTarget.field());
    }

    private long consumeReadClears(Register primary, long value, List<SimEvent> events) {
        long mask = readClearMask(primary);
        if (mask == 0L) {
            return value;
        }
        long after = value & ~mask;
        events.add(new SimEvent("rc", "read-clear bits consumed", primary.name(), value, after));
        return after;
    }

    private long readClearMask(Register primary) {
        long mask = 0L;
        for (Field f : primary.fields()) {
            if (f.access() == Access.RC) {
                mask |= f.mask();
            }
        }
        return mask;
    }

    // ---- helpers --------------------------------------------------------------

    private Register reg(String name) {
        return model.register(name);
    }

    private Register primary(Register r) {
        return r.isAlias() ? model.register(r.aliasOf()) : r;
    }

    private static Field field(Register r, String name) {
        Field f = r.field(name);
        if (f == null) {
            throw new IllegalArgumentException("unknown field: " + r.name() + "." + name);
        }
        return f;
    }

    private static Ref ref(Register r) {
        return Ref.reg(r.name());
    }

    private static long extract(long value, Field f) {
        long v = (value & f.mask()) >>> f.lsb();
        return v;
    }

    private static long setField(long value, Field f, long fieldValue) {
        return (value & ~f.mask()) | ((fieldValue << f.lsb()) & f.mask());
    }

    private long extractWord(long value, int wordIndex) {
        int shift = wordIndex * model.wordBits();
        int wbits = model.wordBits();
        long m = wbits >= 64 ? ~0L : (1L << wbits) - 1L;
        return (value >>> shift) & m;
    }
}
