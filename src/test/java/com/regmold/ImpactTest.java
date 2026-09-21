package com.regmold;

import com.regmold.impact.ApiChange;
import com.regmold.impact.ImpactAnalyzer;
import com.regmold.model.ChipModel;
import com.regmold.yaml.ModelParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ImpactTest {

    private final ImpactAnalyzer analyzer = new ImpactAnalyzer();

    @Test
    void addressChangeListsAffectedApi() {
        ChipModel a = ModelParser.parse("""
            name: t
            registers:
              - { name: CTRL, address: 0x04, width: 32,
                  fields: [ { name: EN, offset: 0, width: 1, access: rw } ] }
            """);
        ChipModel b = ModelParser.parse("""
            name: t
            registers:
              - { name: CTRL, address: 0x40, width: 32,
                  fields: [ { name: EN, offset: 0, width: 1, access: rw } ] }
            """);
        List<ApiChange> changes = analyzer.diff(a, b);
        assertTrue(changes.stream().anyMatch(c ->
                c.category().equals("ABI") && c.symbol().equals("REGMOLD_CTRL_ADDR")));
    }

    @Test
    void widthAndEndiannessChangesAreReported() {
        ChipModel a = ModelParser.parse("""
            name: t
            endianness: little
            registers:
              - { name: CNTR, address: 0x0, width: 32 }
            """);
        ChipModel b = ModelParser.parse("""
            name: t
            endianness: big
            registers:
              - { name: CNTR, address: 0x0, width: 64 }
            """);
        List<ApiChange> changes = analyzer.diff(a, b);
        assertTrue(changes.stream().anyMatch(c -> c.kind().equals("ENDIANNESS_CHANGED")));
        assertTrue(changes.stream().anyMatch(c -> c.kind().equals("WIDTH_CHANGED")));
    }

    @Test
    void removedRegisterRemovesItsSymbols() {
        ChipModel a = ModelParser.parse("""
            name: t
            registers:
              - { name: A, address: 0x0, width: 32 }
              - { name: B, address: 0x4, width: 32 }
            """);
        ChipModel b = ModelParser.parse("""
            name: t
            registers:
              - { name: A, address: 0x0, width: 32 }
            """);
        List<ApiChange> changes = analyzer.diff(a, b);
        assertTrue(changes.stream().anyMatch(c -> c.kind().equals("REMOVED") && c.symbol().equals("REGMOLD_B_ADDR")));
    }
}
