package com.regforge.sim;

import com.regforge.model.ChipModel;
import com.regforge.model.FieldDef;
import com.regforge.model.RegisterDef;
import com.regforge.model.SideEffect;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 寄存器状态机：按位域访问模式应用读写，处理锁存与写触发的副作用级联。
 */
public class Simulator {

    private static final int MAX_CASCADE = 64;

    private final ChipModel model;
    private final Map<String, Long> values = new LinkedHashMap<>();

    public Simulator(ChipModel model) {
        this.model = model;
        reset();
    }

    public final void reset() {
        values.clear();
        for (RegisterDef reg : model.allRegisters()) {
            values.put(reg.name(), reg.reset() & reg.widthMask());
        }
    }

    public Map<String, Long> snapshot() {
        return new LinkedHashMap<>(values);
    }

    public StepResult apply(int index, SimOp op) {
        RegisterDef reg = model.findRegister(op.register())
                .orElseThrow(() -> new IllegalArgumentException("未知寄存器: " + op.register()));
        List<String> log = new ArrayList<>();
        String readValue = null;
        List<String> readWords = null;
        boolean accepted = true;

        switch (op.type()) {
            case READ, PEEK -> {
                long value = values.get(reg.name());
                readValue = hex(value, reg.width());
                readWords = splitWords(reg, value);
                if (op.type() == SimOp.Type.READ) {
                    long cleared = 0L;
                    long newValue = value;
                    for (FieldDef field : reg.fields()) {
                        if (field.access() == com.regforge.model.AccessMode.RC
                                && (value & field.mask()) != 0) {
                            cleared |= field.mask();
                            newValue &= ~field.mask();
                            log.add("读清零: " + reg.name() + "." + field.name()
                                    + "=" + hex((value & field.mask()) >>> field.lsb(), field.width())
                                    + "，读后硬件清零");
                        }
                    }
                    if (cleared != 0L) {
                        values.put(reg.name(), newValue);
                    }
                } else {
                    log.add("调试预览（peek）：不消费读清零字段");
                }
            }
            case WRITE -> {
                if (isLocked(reg, log)) {
                    accepted = false;
                    log.add("写入被锁存拒绝: " + reg.name() + " 受 " + reg.lockedBy() + " 保护");
                } else {
                    accepted = doWrite(reg, op.value() == null ? 0L : op.value(), log);
                }
            }
        }
        return new StepResult(index, op, accepted, readValue, readWords, List.copyOf(log),
                stateView());
    }

    private boolean doWrite(RegisterDef reg, long writeRaw, List<String> log) {
        long write = writeRaw & reg.widthMask();
        long oldValue = values.get(reg.name());
        long newValue = oldValue;
        for (FieldDef field : reg.fields()) {
            long mask = field.mask();
            long incoming = write & mask;
            switch (field.access()) {
                case RW -> newValue = (newValue & ~mask) | incoming;
                case W1C -> {
                    if (incoming != 0) {
                        newValue &= ~incoming;
                        log.add("写一清零: " + reg.name() + "." + field.name());
                    }
                }
                case W1S -> {
                    if (incoming != 0) {
                        newValue |= incoming;
                        log.add("写一置位: " + reg.name() + "." + field.name());
                    }
                }
                case RO -> log.add("只读位忽略写入: " + reg.name() + "." + field.name());
                case RC -> log.add("读清零位忽略直接写入: " + reg.name() + "." + field.name());
                case RESERVED -> log.add("保留位维持读取值: " + reg.name() + "." + field.name());
            }
        }
        long unclaimed = reg.widthMask() & ~reg.fields().stream().mapToLong(FieldDef::mask)
                .reduce(0L, (a, b) -> a | b);
        if (unclaimed != 0L) {
            log.add("未声明位按保留处理，维持读取值: " + reg.name());
        }
        values.put(reg.name(), newValue);

        // 只有被写数据真正触及的位域才触发副作用
        List<FieldDef> touched = new ArrayList<>();
        for (FieldDef field : reg.fields()) {
            if ((write & field.mask()) != 0) {
                touched.add(field);
            }
        }
        if (!touched.isEmpty()) {
            cascade(reg, touched, log);
        }
        return true;
    }

    private void cascade(RegisterDef source, List<FieldDef> touched, List<String> log) {
        record Pending(SideEffect effect, String chain) {
        }
        Deque<Pending> queue = new ArrayDeque<>();
        for (FieldDef field : touched) {
            for (SideEffect effect : source.sideEffects()) {
                if (effect.triggerField().equals(field.name())) {
                    queue.add(new Pending(effect, source.name() + "." + field.name()));
                }
            }
        }
        int applied = 0;
        while (!queue.isEmpty()) {
            Pending pending = queue.poll();
            SideEffect effect = pending.effect();
            RegisterDef targetReg = model.findRegister(effect.targetRegister()).orElseThrow();
            FieldDef targetField = targetReg.field(effect.targetField());
            long mask = targetField.mask();
            long current = values.get(targetReg.name());
            long changed = switch (effect.action()) {
                case SET -> current | mask;
                case CLEAR -> current & ~mask;
                case TOGGLE -> current ^ mask;
                case ASSIGN -> (current & ~mask)
                        | ((effect.value() << targetField.lsb()) & mask);
            };
            values.put(targetReg.name(), changed);
            String chain = pending.chain() + " → " + effect.node()
                    + "[" + effect.action().symbol()
                    + (effect.value() != null ? " " + effect.value() : "") + "]";
            log.add("副作用级联: " + chain);
            for (SideEffect followUp : targetReg.sideEffects()) {
                if (followUp.triggerField().equals(targetField.name())) {
                    if (++applied > MAX_CASCADE) {
                        log.add("副作用级联超过 " + MAX_CASCADE
                                + " 步（存在未拦截的环），中止: " + chain);
                        return;
                    }
                    queue.add(new Pending(followUp, chain));
                }
            }
        }
    }

    private boolean isLocked(RegisterDef reg, List<String> log) {
        if (reg.lockedBy() == null) {
            return false;
        }
        String[] parts = reg.lockedBy().split("\\.", 2);
        RegisterDef lockReg = model.findRegister(parts[0]).orElse(null);
        if (lockReg == null || lockReg.field(parts[1]) == null) {
            return false;
        }
        FieldDef lockField = lockReg.field(parts[1]);
        long current = values.getOrDefault(lockReg.name(), lockReg.reset())
                & lockField.mask();
        current = current >>> lockField.lsb();
        long unlockValue = reg.unlockValue() == null ? 0L : reg.unlockValue();
        return current != unlockValue;
    }

    private List<String> splitWords(RegisterDef reg, long value) {
        if (reg.width() <= 32) {
            return List.of(hex(value, reg.width()));
        }
        int words = (reg.width() + 31) / 32;
        List<String> ordered = new ArrayList<>();
        for (int i = 0; i < words; i++) {
            long word = (value >>> (32L * i)) & 0xFFFFFFFFL;
            ordered.add(hex(word, 32));
        }
        if (reg.wordOrder() == RegisterDef.WordOrder.HIGH_FIRST) {
            java.util.Collections.reverse(ordered);
        }
        return ordered;
    }

    private Map<String, String> stateView() {
        Map<String, String> view = new LinkedHashMap<>();
        values.forEach((k, v) -> model.findRegister(k)
                .ifPresent(r -> view.put(k, hex(v, r.width()))));
        return view;
    }

    private static String hex(long value, int width) {
        int digits = Math.max(1, (width + 3) / 4);
        String raw = Long.toUnsignedString(value, 16);
        return "0x" + "0".repeat(Math.max(0, digits - raw.length())) + raw;
    }
}
