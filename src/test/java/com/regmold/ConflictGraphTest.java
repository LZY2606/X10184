package com.regmold;

import com.regmold.domain.Model;
import com.regmold.validate.ConflictFinder;
import com.regmold.validate.ValidationIssue;
import com.regmold.validate.Validator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConflictGraphTest {

    @Test
    void detectsShortestCycle() {
        Model m = TestModels.parse(Fixtures.CYCLIC);
        ConflictFinder.Result result = new ConflictFinder(m.effects()).analyze();
        assertFalse(result.cycles().isEmpty());
        // X --set--> Y --clear--> Z --set--> X : length 3
        assertEquals(3, result.cycles().get(0).length());
        String rendered = result.cycles().get(0).render();
        assertTrue(rendered.contains("A.X") && rendered.contains("A.Y") && rendered.contains("A.Z"));
    }

    @Test
    void validatorRejectsCycle() {
        List<ValidationIssue> issues = Validator.validate(TestModels.parse(Fixtures.CYCLIC));
        assertTrue(issues.stream().anyMatch(i -> i.code().equals("SIDE_EFFECT_CYCLE")), issues.toString());
    }

    @Test
    void detectsShortestContradiction() {
        Model m = TestModels.parse(Fixtures.CONTRADICTION);
        ConflictFinder.Result result = new ConflictFinder(m.effects()).analyze();
        assertFalse(result.conflicts().isEmpty());
        // Direct X --clear--> Z (1) vs X --set--> Y --set--> Z (2): total 3, target Z
        assertEquals("A.Z", result.conflicts().get(0).target());
        assertEquals(3, result.conflicts().get(0).totalLength());
        List<ValidationIssue> issues = Validator.validate(m);
        assertTrue(issues.stream().anyMatch(i -> i.code().equals("SIDE_EFFECT_CONFLICT")));
    }

    @Test
    void noFalsePositiveForChainWithSameOutcome() {
        String yaml = """
                name: fine
                version: "1"
                registers:
                  - name: A
                    address: 0x0
                    width_bits: 8
                    fields:
                      - {name: X, bits: 0, access: rw}
                      - {name: Y, bits: 1, access: rw}
                effects:
                  - {trigger: A.X, action: set, target: A.Y}
                """;
        ConflictFinder.Result result = new ConflictFinder(TestModels.parse(yaml).effects()).analyze();
        assertTrue(result.cycles().isEmpty());
        assertTrue(result.conflicts().isEmpty());
    }
}
