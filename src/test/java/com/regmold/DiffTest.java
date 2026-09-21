package com.regmold;

import com.regmold.diff.DiffEntry;
import com.regmold.diff.ModelDiffer;
import com.regmold.domain.Model;
import com.regmold.parse.YamlParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DiffTest {

    private final String base = """
            name: t
            data_width: 32
            registers:
              - name: CTRL
                offset: 0x0
                fields:
                  - {name: EN, bits: 0, access: RW}
              - name: DATA
                offset: 0x4
                width: 32
                fields:
                  - {name: V, bits: "[7:0]", access: RW}
            """;

    @Test
    void addressChangeListsAffectedApis() {
        String moved = base.replace("offset: 0x4", "offset: 0x8");
        List<DiffEntry> entries = diff(base, moved);
        DiffEntry e = entries.stream().filter(x -> x.detail.contains("address changed")).findFirst().orElseThrow();
        assertTrue(e.affectedApis.stream().anyMatch(a -> a.contains("data_read")));
    }

    @Test
    void widthChangeIsAbiChange() {
        String wider = base.replace("width: 32", "width: 64");
        List<DiffEntry> entries = diff(base, wider);
        assertTrue(entries.stream().anyMatch(e -> e.kind.equals("ABI") && e.detail.contains("width changed")));
    }

    @Test
    void endiannessChangeIsBehaviorChange() {
        String big = base.replace("name: t\n", "name: t\nendianness: big\n");
        List<DiffEntry> entries = diff(base, big);
        assertTrue(entries.stream().anyMatch(e -> e.kind.equals("BEHAVIOR") && e.scope.equals("endianness")));
    }

    @Test
    void renamedFieldIsReportedNotSilentlyMapped() {
        String renamed = base.replace("name: V,", "name: VAL,");
        List<DiffEntry> entries = diff(base, renamed);
        assertTrue(entries.stream().anyMatch(e -> e.detail.contains("DATA.V") && e.detail.contains("removed")));
        assertTrue(entries.stream().anyMatch(e -> e.detail.contains("DATA.VAL") && e.detail.contains("added")));
    }

    private List<DiffEntry> diff(String a, String b) {
        Model ma = new YamlParser().parse(a).model;
        Model mb = new YamlParser().parse(b).model;
        return new ModelDiffer().diff(ma, mb);
    }
}
