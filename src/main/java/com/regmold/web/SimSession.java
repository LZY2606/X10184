package com.regmold.web;

import com.regmold.domain.Model;
import com.regmold.sim.SimEvent;
import com.regmold.sim.SimResult;
import com.regmold.sim.Simulator;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One interactive read/write rehearsal with an ordered event log. */
public class SimSession {

    private final long id;
    private final long revisionId;
    private final Simulator simulator;
    private final List<Map<String, Object>> log = new ArrayList<>();
    private final Instant createdAt = Instant.now();

    public SimSession(long id, long revisionId, Model model) {
        this.id = id;
        this.revisionId = revisionId;
        this.simulator = new Simulator(model);
    }

    public long id() {
        return id;
    }

    public long revisionId() {
        return revisionId;
    }

    public synchronized void reset() {
        simulator.reset();
        log.clear();
    }

    public synchronized Map<String, Object> state() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("revisionId", revisionId);
        out.put("createdAt", createdAt.toString());
        Map<String, String> regs = new LinkedHashMap<>();
        for (Map.Entry<String, Long> e : simulator.snapshot().entrySet()) {
            regs.put(e.getKey(), "0x" + Long.toUnsignedString(e.getValue(), 16));
        }
        out.put("registers", regs);
        out.put("log", List.copyOf(log));
        return out;
    }

    public synchronized Map<String, Object> write(String reg, long value) {
        SimResult result = simulator.write(reg, value);
        return record("write", reg, value, result);
    }

    public synchronized Map<String, Object> read(String reg, int word) {
        SimResult result = simulator.read(reg, word);
        return record("read", reg + (word > 0 ? "[" + word + "]" : ""), null, result);
    }

    public synchronized Map<String, Object> atomic(String reg, long setMask, long clearMask) {
        SimResult result = simulator.atomicMask(reg, setMask, clearMask);
        return record("atomic", reg, null, result);
    }

    public synchronized Map<String, Object> peek(String reg) {
        long value = simulator.peek(reg);
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("op", "peek");
        entry.put("target", reg);
        entry.put("ok", true);
        entry.put("value", "0x" + Long.toUnsignedString(value, 16));
        entry.put("note", "peek never consumes read-clear bits and never latches");
        return entry;
    }

    private Map<String, Object> record(String op, String target, Long input, SimResult result) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("op", op);
        entry.put("target", target);
        if (input != null) {
            entry.put("input", "0x" + Long.toUnsignedString(input, 16));
        }
        entry.put("ok", result.ok());
        if (result.error() != null) {
            entry.put("error", result.error());
        }
        entry.put("value", "0x" + Long.toUnsignedString(result.value(), 16));
        List<Map<String, Object>> events = new ArrayList<>();
        for (SimEvent e : result.events()) {
            events.add(e.toMap());
        }
        entry.put("events", events);
        log.add(entry);
        Map<String, Object> out = new LinkedHashMap<>(entry);
        out.put("state", stateInternal());
        return out;
    }

    private Map<String, String> stateInternal() {
        Map<String, String> regs = new LinkedHashMap<>();
        for (Map.Entry<String, Long> e : simulator.snapshot().entrySet()) {
            regs.put(e.getKey(), "0x" + Long.toUnsignedString(e.getValue(), 16));
        }
        return regs;
    }
}
