package com.regmold.sim;

import com.regmold.domain.Access;
import com.regmold.domain.EffectAction;
import com.regmold.domain.EffectModel;
import com.regmold.domain.FieldModel;
import com.regmold.domain.RegisterModel;
import com.regmold.validate.ModelIndex;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class Simulator {
    private final ModelIndex index;
    private final Map<String, Long> state = new HashMap<>();
    private final Map<String, Long> writeBuffer = new HashMap<>();
    private final Map<String, Long> readSnapshot = new HashMap<>();
    private final Map<String, Integer> readCursor = new HashMap<>();

    public Simulator(ModelIndex index) {
        this.index = index;
        reset();
    }

    public final void reset() {
        state.clear();
        writeBuffer.clear();
        readSnapshot.clear();
        readCursor.clear();
        for (RegisterModel r : index.model.registers) {
            long value = 0L;
            if (r.reset != null) {
                value = r.reset;
            } else {
                for (FieldModel f : r.fields) {
                    value |= (f.reset & f.linearMask());
                }
            }
            state.put(r.name, value & index.fullMask(index.width(r)));
        }
    }

    public Map<String, String> snapshot() {
        Map<String, String> out = new LinkedHashMap<>();
        for (RegisterModel r : index.model.registers) {
            out.put(r.name, "0x" + Long.toUnsignedString(state.get(r.name), 16));
        }
        return out;
    }

    public List<SimStep> run(List<SimOperation> operations) {
        List<SimStep> steps = new ArrayList<>();
        int i = 0;
        for (SimOperation op : operations) {
            steps.add(execute(op, i++));
        }
        return steps;
    }

    public SimStep peek(String registerName, Integer word) {
        return doRead(registerName, word, true, -1);
    }

    private SimStep execute(SimOperation op, int index0) {
        RegisterModel r = index.byName.get(op.register);
        if (r == null) {
            SimStep err = new SimStep();
            err.index = index0;
            err.type = op.type;
            err.note = "unknown register: " + op.register;
            err.state = snapshot();
            return err;
        }
        return switch (op.type) {
            case "read" -> doRead(op.register, op.word, false, index0);
            case "peek" -> doRead(op.register, op.word, true, index0);
            case "write" -> doWrite(op.register, op.value == null ? 0L : op.value, op.word, index0);
            default -> {
                SimStep err = new SimStep();
                err.index = index0;
                err.type = op.type;
                err.note = "unknown operation type: " + op.type;
                err.state = snapshot();
                yield err;
            }
        };
    }

    private SimStep doRead(String name, Integer word, boolean preview, int idx) {
        RegisterModel r = index.byName.get(name);
        int width = index.width(r);
        int dw = index.model.dataWidth;
        boolean multi = width > dw;
        int words = width / dw;
        int wordIndex = word == null ? 0 : word;

        SimStep step = new SimStep();
        step.index = idx < 0 ? 0 : idx;
        step.type = preview ? "peek" : "read";
        step.register = name;
        step.word = multi ? wordIndex : null;

        long linear;
        if (multi) {
            if (wordIndex < 0 || wordIndex >= words) {
                step.note = "word index out of range";
                step.state = snapshot();
                return step;
            }
            boolean little = !"big".equals(r.endianness != null ? r.endianness : index.model.endianness);
            int first = little ? 0 : words - 1;
            if (preview) {
                linear = state.get(name);
            } else if (wordIndex == first || readSnapshot.containsKey(name)) {
                if (wordIndex == first) {
                    readSnapshot.put(name, state.get(name));
                    readCursor.put(name, 1);
                } else {
                    readCursor.merge(name, 1, Integer::sum);
                }
                linear = readSnapshot.get(name);
                if (readCursor.get(name) >= words) {
                    readSnapshot.remove(name);
                    readCursor.remove(name);
                }
            } else {
                linear = state.get(name);
                step.events.add(new SimEvent("ORDER", "high word read before low word; value may tear"));
            }
        } else {
            linear = state.get(name);
        }

        long value = composeReadValue(r, linear) >>> (wordIndex * dw) & index.fullMask(dw);
        step.value = value;
        step.written = value;

        if (!preview && !r.noClearRead) {
            long rcMask = maskOf(r, Access.RC, width);
            if (multi) {
                long wordMask = index.fullMask(dw) << (wordIndex * dw);
                rcMask &= wordMask;
            }
            if (rcMask != 0) {
                long before = state.get(name);
                long after = before & ~rcMask;
                state.put(name, after);
                SimEvent ev = new SimEvent("RC_CLEAR", "read-clear bits consumed by bus read");
                ev.changes.put(name, after ^ before);
                step.events.add(ev);
            }
        } else if (!preview && r.noClearRead) {
            step.events.add(new SimEvent("NO_CLEAR", "read through alias does not consume read-clear bits"));
        }
        if (preview) {
            step.events.add(new SimEvent("PREVIEW", "debug preview consumes no state"));
        }
        step.state = snapshot();
        return step;
    }

    private long composeReadValue(RegisterModel r, long linear) {
        long value = linear;
        long woMask = maskOf(r, Access.WO, index.width(r));
        value &= ~woMask;
        return value;
    }

    private SimStep doWrite(String name, long busValue, Integer word, int idx) {
        RegisterModel r = index.byName.get(name);
        int width = index.width(r);
        int dw = index.model.dataWidth;
        boolean multi = width > dw;
        int words = width / dw;
        int wordIndex = word == null ? 0 : word;

        SimStep step = new SimStep();
        step.index = idx;
        step.type = "write";
        step.register = name;
        step.word = multi ? wordIndex : null;
        step.written = busValue;

        long fullValue;
        boolean commit = true;
        if (multi) {
            if (wordIndex < 0 || wordIndex >= words) {
                step.note = "word index out of range";
                step.state = snapshot();
                return step;
            }
            long wordMask = index.fullMask(dw);
            long accumulated = writeBuffer.getOrDefault(name, 0L) & ~(wordMask << (wordIndex * dw));
            accumulated |= (busValue & wordMask) << (wordIndex * dw);
            writeBuffer.put(name, accumulated);
            boolean little = !"big".equals(r.endianness != null ? r.endianness : index.model.endianness);
            int last = little ? words - 1 : 0;
            commit = wordIndex == last;
            fullValue = accumulated;
            step.events.add(new SimEvent("WORD_BUFFER",
                    "word " + wordIndex + " buffered; commit on write of word " + last));
            if (!commit) {
                step.value = state.get(name);
                step.state = snapshot();
                return step;
            }
            writeBuffer.remove(name);
        } else {
            fullValue = busValue & index.fullMask(width);
        }

        RegisterModel base = r.aliasOf != null ? index.byName.get(r.aliasOf) : r;
        if (r.lock != null) {
            RegisterModel lockReg = index.byName.get(r.lock.register);
            long lockState = state.get(lockReg.name);
            if (((lockState >>> r.lock.bit) & 1L) == 1L) {
                step.blocked = true;
                step.events.add(new SimEvent("LOCKED",
                        "write blocked by " + r.lock.register + " bit " + r.lock.bit));
                step.value = state.get(base.name);
                step.state = snapshot();
                return step;
            }
        }

        long oldValue = state.get(base.name);
        long newValue;
        if ("set".equals(r.aliasWrite)) {
            newValue = oldValue | fullValue;
        } else if ("clear".equals(r.aliasWrite)) {
            newValue = oldValue & ~fullValue;
        } else {
            newValue = normalWrite(base, oldValue, fullValue);
        }
        newValue &= index.fullMask(width);
        state.put(base.name, newValue);
        step.value = newValue;

        if (oldValue != newValue) {
            SimEvent change = new SimEvent("WRITE", "register updated");
            change.changes.put(base.name, newValue ^ oldValue);
            step.events.add(change);
        }

        long effectiveWrite = switch (r.aliasWrite == null ? "normal" : r.aliasWrite) {
            case "set" -> fullValue & ~oldValue;
            case "clear" -> fullValue & oldValue;
            default -> effectiveNormalWriteMask(base, oldValue, fullValue);
        };
        fireEffects(base, effectiveWrite, step, new ArrayDeque<>(), new HashSet<>());
        step.state = snapshot();
        return step;
    }

    private long effectiveNormalWriteMask(RegisterModel r, long oldValue, long data) {
        long mask = 0L;
        for (FieldModel f : r.fields) {
            long m = f.linearMask();
            switch (f.access) {
                case RW, WO -> mask |= (data & m) & ~(oldValue & m);
                case W1C -> mask |= (data & m) & (oldValue & m);
                case W1S -> mask |= (data & m) & ~(oldValue & m);
                default -> {
                }
            }
        }
        return mask;
    }

    private long normalWrite(RegisterModel r, long oldValue, long data) {
        long result = oldValue;
        for (FieldModel f : r.fields) {
            long mask = f.linearMask();
            switch (f.access) {
                case RW, WO -> result = (result & ~mask) | (data & mask);
                case W1C -> result &= ~(data & mask);
                case W1S -> result |= (data & mask);
                default -> {
                }
            }
        }
        return result;
    }

    private void fireEffects(RegisterModel triggerReg, long accessMask, SimStep step,
                             Deque<String> chain, Set<String> chainKeys) {
        List<EffectModel> firedNow = new ArrayList<>();
        for (EffectModel e : index.effectsTriggeredBy(triggerReg.name)) {
            if ((accessMask & e.triggerMask) != 0) {
                firedNow.add(e);
            }
        }
        for (EffectModel e : firedNow) {
            String key = triggerReg.name + "->" + e.name;
            if (!chainKeys.add(key)) {
                step.events.add(new SimEvent("CYCLE", "side-effect cycle cut at " + e.name));
                continue;
            }
            chain.addLast(e.name);
            RegisterModel targetReg = index.byName.get(e.targetRegister);
            long before = state.get(e.targetRegister);
            long after = before;
            FieldModel tf = index.field(e.targetRegister, e.targetField);
            long mask = tf != null ? tf.linearMask() : index.fullMask(index.width(targetReg));
            switch (e.action) {
                case SET -> after |= mask;
                case CLEAR -> after &= ~mask;
                case LATCH -> {
                    String srcReg = e.sourceRegister != null ? e.sourceRegister
                            : (tf != null && tf.latchFrom != null ? tf.latchFrom.split("\\.")[0] : null);
                    String srcField = e.sourceField != null ? e.sourceField
                            : (tf != null && tf.latchFrom != null ? tf.latchFrom.split("\\.")[1] : null);
                    long srcRaw = srcReg != null ? state.getOrDefault(srcReg, 0L) : 0L;
                    FieldModel sf = index.field(srcReg, srcField);
                    long srcBits = sf != null
                            ? ((srcRaw & sf.linearMask()) >>> sf.bitStart) << tf.bitStart
                            : (srcRaw << tf.bitStart) & mask;
                    after = (after & ~mask) | (srcBits & mask);
                }
            }
            state.put(e.targetRegister, after);
            SimEvent ev = new SimEvent("EFFECT", "effect " + e.name + " fired", e.name);
            ev.changes.put(e.targetRegister, after ^ before);
            step.events.add(ev);
            fireEffects(targetReg, mask, step, chain, chainKeys);
            chain.removeLast();
            chainKeys.remove(key);
        }
    }

    private long maskOf(RegisterModel r, Access access, int width) {
        long mask = 0L;
        for (FieldModel f : r.fields) {
            if (f.access == access) {
                mask |= f.linearMask();
            }
        }
        return mask & index.fullMask(width);
    }
}
