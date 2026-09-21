package com.regmold;

import com.regmold.model.ChipModel;
import com.regmold.sim.SimOp;
import com.regmold.sim.SimStep;
import com.regmold.sim.Simulator;
import com.regmold.yaml.ModelParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SimulatorTest {

    static final String MODEL = """
        name: sim-chip
        registers:
          - name: STATUS
            address: 0x00
            width: 32
            reset: 0x000A0002
            fields:
              - { name: ERR, offset: 1, width: 1, access: w1c,
                  sideEffects: [ { target: CTRL.STOP, action: set } ] }
              - { name: COUNT, offset: 8, width: 8, access: rc, reset: 0x0A }
              - { name: RSVD, offset: 16, width: 4, access: reserved, reset: 0x5 }
          - name: CTRL
            address: 0x04
            width: 32
            lockedBy: { register: LOCK, field: KEY_OK, openValue: 1 }
            fields:
              - { name: ENABLE, offset: 0, width: 1, access: rw }
              - { name: STOP, offset: 1, width: 1, access: rw }
          - name: LOCK
            address: 0x08
            width: 32
            fields:
              - { name: KEY_OK, offset: 0, width: 1, access: rw }
          - name: COUNTER64
            address: 0x10
            width: 64
            wordOrder: hi-first
            fields:
              - { name: VALUE, offset: 28, width: 12, access: rw }
          - name: GPIO
            address: 0x20
            width: 32
            fields:
              - { name: PIN, offset: 0, width: 8, access: rw }
          - { name: GPIO_SET, address: 0x20, width: 32, aliasOf: GPIO, semantic: w1s }
          - { name: GPIO_CLR, address: 0x20, width: 32, aliasOf: GPIO, semantic: w1c }
        """;

    ChipModel model() { return ModelParser.parse(MODEL); }

    @Test
    void crossWordFieldMask() {
        ChipModel m = model();
        var f = m.register("COUNTER64").field("VALUE");
        assertEquals(0xFFF0000000L, f.mask());
        assertTrue(f.crossesWord());
    }

    @Test
    void w1cClearsOnlyWrittenBits() {
        Simulator sim = new Simulator(model());
        List<SimStep> steps = sim.run(List.of(SimOp.write("STATUS", 0x2)));
        // ERR was 1 at reset; writing 1 clears it (reserved nibble 0x5 and RC count survive)
        assertEquals("0x50a00", steps.get(0).stateAfter().get("STATUS"));
    }

    @Test
    void reservedBitsKeepReadValue() {
        Simulator sim = new Simulator(model());
        List<SimStep> steps = sim.run(List.of(SimOp.write("STATUS", 0xF0000)));
        // reserved field reset to 0x5 << 16 must survive the write-back
        assertEquals("0x50a02", steps.get(0).stateAfter().get("STATUS"));
        assertTrue(steps.get(0).events().stream().anyMatch(e -> e.contains("reserved")));
    }

    @Test
    void readClearConsumedByReadButNotPreview() {
        Simulator sim = new Simulator(model());
        List<SimStep> steps = sim.run(List.of(
                SimOp.preview("STATUS"), SimOp.preview("STATUS"), SimOp.read("STATUS"), SimOp.read("STATUS")));
        assertEquals("0x50a02", steps.get(0).value());
        assertEquals("0x50a02", steps.get(1).value()); // preview does not consume
        assertEquals("0x50a02", steps.get(2).value()); // real read returns then clears
        assertEquals("0x50002", steps.get(3).value()); // COUNT now consumed
    }

    @Test
    void lockBlocksWritesUntilOpen() {
        Simulator sim = new Simulator(model());
        List<SimStep> steps = sim.run(List.of(
                SimOp.write("CTRL", 0x1),
                SimOp.write("LOCK", 0x1),
                SimOp.write("CTRL", 0x1)));
        assertTrue(steps.get(0).events().stream().anyMatch(e -> e.contains("BLOCKED")));
        assertEquals("0x0", steps.get(0).stateAfter().get("CTRL"));
        assertEquals("0x1", steps.get(2).stateAfter().get("CTRL"));
    }

    @Test
    void aliasesShareStateWithAtomicSetClear() {
        Simulator sim = new Simulator(model());
        List<SimStep> steps = sim.run(List.of(
                SimOp.write("GPIO_SET", 0x5),
                SimOp.write("GPIO_CLR", 0x1),
                SimOp.read("GPIO")));
        assertEquals("0x5", steps.get(0).stateAfter().get("GPIO"));
        assertEquals("0x4", steps.get(1).stateAfter().get("GPIO"));
        assertEquals("0x4", steps.get(2).value());
    }

    @Test
    void multiWordReadHonorsHiFirstOrder() {
        Simulator sim = new Simulator(model());
        List<SimStep> steps = sim.run(List.of(
                SimOp.write("COUNTER64", 0xABC0000000L),
                SimOp.read("COUNTER64")));
        String evt = steps.get(1).events().get(0);
        assertTrue(evt.contains("hi-first"));
        assertTrue(evt.indexOf("word1=") < evt.indexOf("word0="), evt);
        assertEquals("0xabc0000000", steps.get(1).value());
    }

    @Test
    void sideEffectCascadesToOtherRegister() {
        Simulator sim = new Simulator(model());
        List<SimStep> steps = sim.run(List.of(
                SimOp.write("LOCK", 0x1),
                SimOp.write("STATUS", 0x2)));
        // clearing ERR sets CTRL.STOP as a side effect
        assertEquals("0x2", steps.get(1).stateAfter().get("CTRL"));
        assertTrue(steps.get(1).events().stream().anyMatch(e -> e.contains("side-effect SET CTRL.STOP")));
    }
}
