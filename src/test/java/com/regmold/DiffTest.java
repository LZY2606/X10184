package com.regmold;

import com.regmold.diff.ModelDiff;
import com.regmold.domain.Model;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DiffTest {

    private static final String V1 = """
            name: d
            version: "1"
            registers:
              - name: R
                address: 0x100
                width_bits: 32
                fields:
                  - {name: F, bits: [3, 0], access: rw}
            """;

    private static final String V2 = """
            name: d
            version: "2"
            registers:
              - name: R
                address: 0x200
                width_bits: 32
                fields:
                  - {name: F, bits: [7, 4], access: rw}
                  - {name: G, bits: 3, access: rw}
            """;

    @Test
    void listsAffectedApisForAddressChange() {
        Model a = TestModels.parse(V1);
        Model b = TestModels.parse(V2);
        ModelDiff.Report report = ModelDiff.compare(a, b);
        boolean addressChange = report.entries().stream()
                .anyMatch(e -> e.message().contains("address") && !e.affectedApis().isEmpty());
        assertTrue(addressChange, report.entries().toString());
        assertTrue(report.entries().stream().anyMatch(e -> e.message().contains("geometry")));
        assertTrue(report.addedApis().stream().anyMatch(s -> s.contains("g")));
    }

    @Test
    void endiannessChangeImpactsAllApis() {
        Model a = TestModels.parse(V1.replace("name: d", "name: d\nendianness: le"));
        String v2be = V2.replace("name: d", "name: d\nendianness: be");
        Model b = TestModels.parse(v2be);
        ModelDiff.Report report = ModelDiff.compare(a, b);
        assertTrue(report.entries().stream().anyMatch(e -> e.message().contains("endianness")));
        assertTrue(report.impactedApis().size() > 4);
    }
}
