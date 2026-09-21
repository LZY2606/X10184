package com.regmold;

import com.regmold.domain.Diagnostic;
import com.regmold.sim.SimOperation;
import com.regmold.sim.SimStep;
import com.regmold.sim.Simulator;
import com.regmold.validate.ModelIndex;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EngineTest {

    @Test
    void crossWordFieldRequiresMultiWordWidth() {
        String yaml = """
                name: t
                data_width: 32
                registers:
                  - name: R
                    offset: 0
                    fields:
                      - {name: HI, bits: "[40:32]", access: RW}
                """;
        assertTrue(TestSupport.hasError(TestSupport.validate(yaml), "BIT_RANGE"));
    }

    @Test
    void multiWordRegisterAcceptedWithCrossWordField() {
        String yaml = """
                name: t
                data_width: 32
                registers:
                  - name: R
                    offset: 0
                    width: 64
                    fields:
                      - {name: P, bits: "[47:0]", access: RW}
                      - {name: Q, bits: "[63:48]", access: RW}
                """;
        assertTrue(TestSupport.validate(yaml).stream().noneMatch(d -> d.severity == Diagnostic.Severity.ERROR));
    }

    @Test
    void aliasWithDifferentWriteSemanticsAccepted() {
        List<Diagnostic> ds = TestSupport.validate(TestSupport.fixture("sample.yaml"));
        assertTrue(ds.stream().noneMatch(d -> d.severity == Diagnostic.Severity.ERROR), ds.toString());
    }

    @Test
    void overlappingFieldsRejected() {
        String yaml = """
                name: t
                data_width: 32
                registers:
                  - name: R
                    offset: 0
                    fields:
                      - {name: A, bits: "[3:0]", access: RW}
                      - {name: B, bits: "[7:2]", access: RW}
                """;
        assertTrue(TestSupport.hasError(TestSupport.validate(yaml), "FIELD_OVERLAP"));
    }

    @Test
    void reservedBitsWrittenBackFromReadValue() {
        Simulator sim = new Simulator(TestSupport.index(TestSupport.fixture("sample.yaml")));
        SimStep write = sim.run(List.of(new SimOperation("write", "CTRL", 0xFFFF_FFFFL, null))).get(0);
        // reserved bits [7:4] hold the read value (zero); ENABLE set
        assertEquals(1L, write.value & 0xF1L);
        assertEquals(0L, write.value & 0xF0L);
    }

    @Test
    void readClearConsumedOnceOnly() {
        Simulator sim = new Simulator(TestSupport.index(TestSupport.fixture("sample.yaml")));
        List<SimStep> steps = sim.run(List.of(
                new SimOperation("read", "STATUS", null, null),
                new SimOperation("read", "STATUS", null, null)));
        assertEquals(0b11L, steps.get(0).value);
        assertEquals(0b10L, steps.get(1).value);
    }

    @Test
    void debugPreviewDoesNotConsumeReadClear() {
        Simulator sim = new Simulator(TestSupport.index(TestSupport.fixture("sample.yaml")));
        SimStep peek = sim.peek("STATUS", null);
        assertEquals(1L, peek.value);
        SimStep read = sim.run(List.of(new SimOperation("read", "STATUS", null, null))).get(0);
        assertEquals(0b11L, read.value);
    }

    @Test
    void writeOneClearClearsOnlyWrittenSetBits() {
        Simulator sim = new Simulator(TestSupport.index(TestSupport.fixture("sample.yaml")));
        sim.run(List.of(
                new SimOperation("write", "STATUS", 0b10L, null),
                new SimOperation("write", "STATUS", 0b10L, null)));
        SimStep s = sim.run(List.of(new SimOperation("peek", "STATUS", null, null))).get(0);
        assertEquals(0L, s.value & 0b10L);
    }

    @Test
    void atomicSetAndClearAliases() {
        Simulator sim = new Simulator(TestSupport.index(TestSupport.fixture("sample.yaml")));
        sim.run(List.of(new SimOperation("write", "SET", 0b1L, null)));
        assertEquals(1L, stateOf(sim, "CTRL"));
        sim.run(List.of(new SimOperation("write", "CLR", 0b1L, null)));
        assertEquals(0L, stateOf(sim, "CTRL"));
    }

    @Test
    void lockedRegisterBlocksWrites() {
        Simulator sim = new Simulator(TestSupport.index(TestSupport.fixture("sample.yaml")));
        sim.run(List.of(new SimOperation("write", "LOCK", 1L, null)));
        SimStep blocked = sim.run(List.of(new SimOperation("write", "SHADOW", 0xABL, null))).get(0);
        assertTrue(blocked.blocked);
        assertEquals(0L, stateOf(sim, "SHADOW"));
    }

    @Test
    void effectLatchCopiesSourceField() {
        Simulator sim = new Simulator(TestSupport.index(TestSupport.fixture("sample.yaml")));
        sim.run(List.of(
                new SimOperation("write", "BURST", 0x0000_0000_0000_0000L, 0),
                new SimOperation("write", "BURST", 0x007BL, 1),
                new SimOperation("write", "STATUS", 0b010L, null)));
        assertEquals(0x7BL, stateOf(sim, "SHADOW") & 0xFF);
    }

    @Test
    void multiwordLowWordBuffersAndHighCommits() {
        Simulator sim = new Simulator(TestSupport.index(TestSupport.fixture("sample.yaml")));
        List<SimStep> steps = sim.run(List.of(
                new SimOperation("write", "BURST", 0xDEAD_BEEFL, 0),
                new SimOperation("write", "BURST", 0x1234_5678L, 1)));
        assertEquals(0L, steps.get(0).value);
        long full = stateOf(sim, "BURST");
        assertEquals(0x1234_5678L, full >>> 32);
        assertEquals(0xDEAD_BEEFL, full & 0xFFFF_FFFFL);
    }

    @Test
    void cycleProducesShortestConflictPath() {
        List<Diagnostic> ds = TestSupport.validate(TestSupport.fixture("cycle.yaml"));
        Diagnostic cycle = ds.stream().filter(d -> d.code.equals("SIDE_EFFECT_CYCLE")).findFirst().orElseThrow();
        assertEquals(List.of("A", "B", "C", "A"), cycle.path);
        assertEquals("c_to_a", cycle.effect);
    }

    private long stateOf(Simulator sim, String name) {
        return Long.parseUnsignedLong(sim.snapshot().get(name).substring(2), 16);
    }
}
