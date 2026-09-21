package com.regmold;

import com.regmold.model.ChipModel;
import com.regmold.validate.Conflict;
import com.regmold.validate.Validator;
import com.regmold.yaml.ModelParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ValidatorTest {

    private final Validator validator = new Validator();

    @Test
    void detectsFieldOverlap() {
        ChipModel m = ModelParser.parse("""
            name: t
            registers:
              - name: R
                address: 0x0
                width: 32
                fields:
                  - { name: A, offset: 0, width: 4, access: rw }
                  - { name: B, offset: 3, width: 4, access: rw }
            """);
        List<Conflict> cs = validator.validate(m);
        assertTrue(cs.stream().anyMatch(c -> c.code().equals("FIELD_OVERLAP")));
    }

    @Test
    void detectsAddressConflictBetweenStrangersButNotAliases() {
        ChipModel bad = ModelParser.parse("""
            name: t
            registers:
              - { name: A, address: 0x0, width: 32 }
              - { name: B, address: 0x0, width: 32 }
            """);
        assertTrue(validator.validate(bad).stream().anyMatch(c -> c.code().equals("ADDRESS_CONFLICT")));
        ChipModel ok = ModelParser.parse("""
            name: t
            registers:
              - { name: A, address: 0x0, width: 32, fields: [ { name: F, offset: 0, width: 1, access: rw } ] }
              - { name: A_SET, address: 0x0, width: 32, aliasOf: A, semantic: w1s }
            """);
        assertTrue(validator.validate(ok).stream().noneMatch(c -> c.code().equals("ADDRESS_CONFLICT")));
    }

    @Test
    void detectsSideEffectCycleWithShortestPath() {
        ChipModel m = ModelParser.parse("""
            name: t
            registers:
              - name: A
                address: 0x0
                width: 32
                fields:
                  - { name: X, offset: 0, width: 1, access: rw,
                      sideEffects: [ { target: B.Y, action: set } ] }
              - name: B
                address: 0x4
                width: 32
                fields:
                  - { name: Y, offset: 0, width: 1, access: rw,
                      sideEffects: [ { target: A.X, action: set } ] }
            """);
        List<Conflict> cs = validator.validate(m);
        Conflict cycle = cs.stream().filter(c -> c.code().equals("SIDE_EFFECT_CYCLE")).findFirst().orElseThrow();
        assertEquals(List.of("A.X", "B.Y", "A.X"), cycle.path());
    }

    @Test
    void detectsContradictoryChainsWithShortestConflictPath() {
        ChipModel m = ModelParser.parse("""
            name: t
            registers:
              - name: SRC
                address: 0x0
                width: 32
                fields:
                  - { name: GO, offset: 0, width: 1, access: rw,
                      sideEffects: [ { target: P.Q, action: set }, { target: R.S, action: clear } ] }
              - name: P
                address: 0x4
                width: 32
                fields:
                  - { name: Q, offset: 0, width: 1, access: rw,
                      sideEffects: [ { target: T.U, action: set } ] }
              - name: R
                address: 0x8
                width: 32
                fields:
                  - { name: S, offset: 0, width: 1, access: rw,
                      sideEffects: [ { target: T.U, action: clear } ] }
              - name: T
                address: 0xC
                width: 32
                fields:
                  - { name: U, offset: 0, width: 1, access: rw }
            """);
        List<Conflict> cs = validator.validate(m);
        Conflict c = cs.stream().filter(x -> x.code().equals("SIDE_EFFECT_CONTRADICTION")).findFirst().orElseThrow();
        assertEquals(3, c.path().size(), "shortest conflict path has 3 nodes: " + c.path());
        assertEquals("SRC.GO", c.path().get(0));
        assertEquals("T.U", c.path().get(2));
    }

    @Test
    void detectsMissingLockAndEffectTargets() {
        ChipModel m = ModelParser.parse("""
            name: t
            registers:
              - name: A
                address: 0x0
                width: 32
                lockedBy: { register: NOPE, field: X, openValue: 1 }
                fields:
                  - { name: F, offset: 0, width: 1, access: rw,
                      sideEffects: [ { target: GONE.F, action: set } ] }
            """);
        List<Conflict> cs = validator.validate(m);
        assertTrue(cs.stream().anyMatch(c -> c.code().equals("LOCK_TARGET_MISSING")));
        assertTrue(cs.stream().anyMatch(c -> c.code().equals("EFFECT_TARGET_MISSING")));
    }

    @Test
    void sampleModelIsClean() throws Exception {
        String yaml = new String(getClass().getResourceAsStream("/static/sample.yaml").readAllBytes());
        List<Conflict> cs = validator.validate(ModelParser.parse(yaml));
        assertTrue(cs.isEmpty(), () -> cs.toString());
    }
}
