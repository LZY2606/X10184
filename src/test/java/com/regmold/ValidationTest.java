package com.regmold;

import com.regmold.domain.Model;
import com.regmold.validate.ValidationIssue;
import com.regmold.validate.Validator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValidationTest {

    @Test
    void validBaseModelHasNoErrors() {
        List<ValidationIssue> issues = Validator.validate(TestModels.parse(Fixtures.BASE));
        assertTrue(issues.stream().noneMatch(i -> i.severity() == ValidationIssue.Severity.ERROR),
                issues.toString());
    }

    @Test
    void detectsOverlappingFields() {
        List<ValidationIssue> issues = Validator.validate(TestModels.parse(Fixtures.OVERLAP));
        assertTrue(issues.stream().anyMatch(i -> i.code().equals("FIELD_OVERLAP")));
    }

    @Test
    void detectsCrossWordFieldAndMissingReadOrder() {
        List<ValidationIssue> issues = Validator.validate(TestModels.parse(Fixtures.CROSSWORD_INVALID));
        assertTrue(issues.stream().anyMatch(i -> i.code().equals("FIELD_CROSSES_WORD"))
                        || issues.stream().anyMatch(i -> i.code().equals("MULTIWORD_ORDER")),
                issues.toString());
    }

    @Test
    void detectsAliasAddressMismatch() {
        String yaml = """
                name: al
                version: "1"
                registers:
                  - name: R
                    address: 0x10
                    width_bits: 32
                  - name: A
                    address: 0x20
                    width_bits: 32
                    access: w1s
                    alias_of: R
                """;
        List<ValidationIssue> issues = Validator.validate(TestModels.parse(yaml));
        assertTrue(issues.stream().anyMatch(i -> i.code().equals("ALIAS_ADDR")));
    }

    @Test
    void detectsResetMismatch() {
        String yaml = """
                name: rm
                version: "1"
                registers:
                  - name: R
                    address: 0x0
                    width_bits: 8
                    reset: 0xff
                    fields:
                      - {name: F, bits: [3, 0], access: rw}
                """;
        List<ValidationIssue> issues = Validator.validate(TestModels.parse(yaml));
        assertTrue(issues.stream().anyMatch(i -> i.code().equals("RESET_MISMATCH")));
    }
}
