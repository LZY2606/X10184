package com.regforge.sim;

import com.regforge.model.Field;
import com.regforge.model.FieldAccess;
import com.regforge.model.FieldEffect;
import com.regforge.model.Register;
import com.regforge.model.RegisterModel;
import com.regforge.util.Numbers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A deterministic behavioral simulator.
 *
 * <p>State is kept per <em>backing</em> register (aliases share storage).
 * Reads come in two flavors: {@code read-preview} never mutates (RC fields are
 * not consumed), while {@code read-consume} clears RC fields.  Writes apply
 * per-field access semantics, keep reserved bits at their read value, honor
 * locks and cascade side effects.
 */
public class Simulator {

    private final RegisterModel model;
    private final Map<String, Long> storage = new LinkedHashMap<>();
    private int stepCount = 0;

    public Simulator(RegisterModel model) {
        this.model = model;
        reset();
    }

    public final void reset() {
        storage.clear();
        for (Register r : model.getRegisters()) {
            if (!r.isAlias()) storage.put(r.getName(), r.getReset());
        }
        stepCount = 0;
    }

    public Map<String, Long> snapshot() {
        Map<String, Long> out = new LinkedHashMap<>();
        for (Register r : model.getRegisters()) {
            out.put(r.getName(), backingValue(r));
        }
        return out;
    }

    private Register backing(Register r) {
        return r.isAlias() ? model.findRegister(r.getAliasOf()) : r;
    }

    private long backingValue(Register r) {
        Register b = backing(r);
        return storage.getOrDefault(b.getName(), 0L);
    }

    private void setBacking(Register r, long value) {
        Register b = backing(r);
        storage.put(b.getName(), value);
    }

    public StepResult step(String type, String targetName, Long value) {
        stepCount++;
        List<SimEvent> events = new ArrayList<>();
        List<Long> readWords = new ArrayList<>();
        long writeValue = value == null ? 0L : value;

        Register reg = model.findRegister(targetName);
        if (reg == null) {
            events.add(SimEvent.of("error", "寄存器不存在: " + targetName, List.of(targetName)));
            return new StepResult(stepCount, type, targetName, writeValue, readWords, events, snapshot());
        }

        switch (type) {
            case "read-preview" -> readWords.addAll(doRead(reg, false, events));
            case "read-consume" -> readWords.addAll(doRead(reg, true, events));
            case "write" -> doWrite(reg, writeValue, events, new ArrayList<>());
            case "atomic-set" -> doAtomic(reg, writeValue, true, events);
            case "atomic-clear" -> doAtomic(reg, writeValue, false, events);
            default -> events.add(SimEvent.of("error", "未知步骤类型: " + type, List.of()));
        }
        return new StepResult(stepCount, type, targetName, writeValue, readWords, events, snapshot());
    }

    private List<Long> doRead(Register reg, boolean consume, List<SimEvent> events) {
        long value = backingValue(reg);
        List<Long> words = splitWords(reg, value);
        if (reg.highFirst()) {
            // hardware returns high word first for big-endian / high_first ordering
            List<Long> reversed = new ArrayList<>();
            for (int i = words.size() - 1; i >= 0; i--) reversed.add(words.get(i));
            words = reversed;
        }
        events.add(SimEvent.of("read",
                (consume ? "读并消费 " : "预览读取 ") + reg.getName()
                        + " = " + Numbers.hex64(value)
                        + " (次序 " + (reg.highFirst() ? "high-first" : "low-first") + ")",
                List.of(reg.getName())));

        if (consume) {
            long cleared = value;
            Register backing = backing(reg);
            for (Field f : backing.getFields()) {
                if (f.getAccess() == FieldAccess.RC) {
                    cleared &= ~f.mask();
                    events.add(SimEvent.of("rc-clear",
                            "读清零: " + backing.getName() + "." + f.getName(),
                            List.of(backing.getName(), f.getName())));
                }
            }
            if (cleared != value) setBacking(reg, cleared);
        } else {
            boolean hasRc = backing(reg).getFields().stream().anyMatch(f -> f.getAccess() == FieldAccess.RC);
            if (hasRc) {
                events.add(SimEvent.of("rc-preview",
                        "预览不会消费读清零字段", List.of(reg.getName())));
            }
        }
        return words;
    }

    private void doWrite(Register reg, long data, List<SimEvent> events, List<String> effectStack) {
        Register backing = backing(reg);
        long current = backingValue(reg);
        long next = current;

        // locked?
        Field lockField = resolveLockField(backing);
        if (lockField != null && isLocked(backing)) {
            events.add(SimEvent.of("locked",
                    "寄存器 " + backing.getName() + " 被锁，写入被忽略 (" + backing.getLockedBy() + ")",
                    List.of(backing.getName())));
            return;
        }

        List<String> fired = new ArrayList<>();
        for (Field f : backing.getFields()) {
            long fieldBits = data & f.mask();
            switch (f.getAccess()) {
                case RESERVED -> {
                    // write back the read value: leave current bits untouched
                    events.add(SimEvent.of("reserved",
                            "保留位 " + f.getName() + " 写回保持读取值",
                            List.of(backing.getName(), f.getName())));
                }
                case RO, RC -> events.add(SimEvent.of("read-only",
                        f.getName() + " 为只读/读清零，写忽略", List.of(f.getName())));
                case W1C -> {
                    if (fieldBits != 0) {
                        next &= ~fieldBits;
                        fired.add(f.getName());
                    }
                }
                case W1S -> {
                    if (fieldBits != 0) {
                        next |= fieldBits;
                        fired.add(f.getName());
                    }
                }
                case W1 -> { if (fieldBits != 0) fired.add(f.getName()); }
                case RW -> {
                    next = (next & ~f.mask()) | (data & f.mask());
                }
            }
        }
        setBacking(reg, next);
        events.add(SimEvent.of("write",
                reg.getName() + (reg.isAlias() ? "（别名→" + backing.getName() + "）" : "")
                        + " <= " + Numbers.hex64(data) + " => " + Numbers.hex64(next)
                        + (fired.isEmpty() ? "" : "，触发位域 " + fired),
                List.of(reg.getName())));

        // side effects after the value settles
        for (Field f : backing.getFields()) {
            long fieldBits = data & f.mask();
            boolean wroteOne = fieldBits != 0;
            if (!wroteOne) continue;
            for (FieldEffect eff : f.getEffects()) {
                applyEffect(backing.getName() + "." + f.getName(), eff, events, effectStack);
            }
        }
    }

    private void doAtomic(Register reg, long mask, boolean set, List<SimEvent> events) {
        Register backing = backing(reg);
        long current = backingValue(reg);
        long next = set ? current | mask : current & ~mask;
        setBacking(reg, next);
        events.add(SimEvent.of("atomic",
                (set ? "原子置位 " : "原子清零 ") + backing.getName()
                        + " mask=" + Numbers.hex64(mask) + " => " + Numbers.hex64(next),
                List.of(backing.getName())));
    }

    private void applyEffect(String source, FieldEffect eff, List<SimEvent> events, List<String> stack) {
        String cycleKey = source + "->" + eff.display() + "->" + eff.target();
        if (stack.contains(cycleKey)) {
            events.add(SimEvent.of("cycle-guard",
                    "运行时阻断循环副作用: " + String.join(" -> ", stack) + " -> " + eff.target(),
                    List.of(eff.target())));
            return;
        }
        List<String> nextStack = new ArrayList<>(stack);
        nextStack.add(cycleKey);

        String target = eff.target();
        Register targetReg;
        Field targetField = null;
        if (target.contains(".")) {
            targetReg = model.findRegister(target.substring(0, target.indexOf('.')));
            String fn = target.substring(target.indexOf('.') + 1);
            if (targetReg != null) {
                for (Field f : targetReg.getFields()) if (f.getName().equals(fn)) targetField = f;
            }
        } else {
            targetReg = model.findRegister(target);
        }
        if (targetReg == null) {
            events.add(SimEvent.of("effect-error", "目标不存在: " + target, List.of(target)));
            return;
        }
        Register tb = backing(targetReg);
        long tv = backingValue(targetReg);
        long mask = targetField != null ? targetField.mask() : ~0L;
        switch (eff.normalizedAction()) {
            case "set" -> { storage.put(tb.getName(), tv | mask); }
            case "clear" -> { storage.put(tb.getName(), tv & ~mask); }
            case "latch" -> {
                // snapshot source register value into the target field
                String srcRegName = source.substring(0, source.indexOf('.'));
                Register srcReg = model.findRegister(srcRegName);
                long sv = srcReg == null ? 0L : backingValue(srcReg);
                if (targetField != null) {
                    long grabbed = (sv >>> targetField.getLsb()) & (targetField.width() >= 64 ? -1L : (1L << targetField.width()) - 1);
                    storage.put(tb.getName(), (tv & ~mask) | (grabbed << targetField.getLsb()));
                }
            }
            default -> { }
        }
        events.add(SimEvent.of("effect",
                source + " " + eff.normalizedAction() + " " + target,
                List.of(source, eff.target())));

        // cascade: if the effect wrote to a W1S-like trigger field, chain its effects
        if (targetField != null) {
            for (FieldEffect chained : targetField.getEffects()) {
                applyEffect(targetReg.getName() + "." + targetField.getName(), chained, events, nextStack);
            }
        }
    }

    private Field resolveLockField(Register r) {
        if (r.getLockedBy() == null || !r.getLockedBy().contains(".")) return null;
        String rn = r.getLockedBy().substring(0, r.getLockedBy().indexOf('.'));
        String fn = r.getLockedBy().substring(r.getLockedBy().indexOf('.') + 1);
        Register lr = model.findRegister(rn);
        if (lr == null) return null;
        return lr.getFields().stream().filter(f -> f.getName().equals(fn)).findFirst().orElse(null);
    }

    private boolean isLocked(Register r) {
        long lvl = Numbers.parseLong(r.getLockLevel(), 1L);
        Field lf = resolveLockField(r);
        if (lf == null) return false;
        Register lr = model.findRegister(r.getLockedBy().substring(0, r.getLockedBy().indexOf('.')));
        long val = backingValue(lr);
        long fieldVal = (val >>> lf.getLsb()) & (lf.width() >= 64 ? -1L : (1L << lf.width()) - 1);
        return fieldVal == lvl;
    }

    private List<Long> splitWords(Register reg, long value) {
        int words = reg.wordCount(model.getWordBits());
        List<Long> out = new ArrayList<>();
        for (int i = 0; i < words; i++) {
            out.add((value >>> (i * model.getWordBits())) & model.wordMask());
        }
        return out;
    }
}
