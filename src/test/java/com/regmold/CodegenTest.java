package com.regmold;

import com.regmold.codegen.ArtifactWriter;
import com.regmold.codegen.Codegen;
import com.regmold.model.ChipModel;
import com.regmold.yaml.ModelParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class CodegenTest {

    static final String YAML_A = """
        name: det-chip
        endianness: little
        registers:
          - name: STATUS
            address: 0x00
            width: 32
            reset: 0x1
            fields:
              - name: ERR
                offset: 1
                width: 1
                access: w1c
              - name: MODE
                offset: 4
                width: 2
                access: rw
        """;

    /** Same semantics, different key order and field order. */
    static final String YAML_B = """
        endianness: little
        name: det-chip
        registers:
          - width: 32
            reset: 0x1
            fields:
              - access: rw
                width: 2
                offset: 4
                name: MODE
              - width: 1
                access: w1c
                name: ERR
                offset: 1
            name: STATUS
            address: 0x00
        """;

    @Test
    void sameSemanticsDifferentKeyOrderProducesIdenticalFiles() {
        ChipModel a = ModelParser.parse(YAML_A);
        ChipModel b = ModelParser.parse(YAML_B);
        assertEquals(ModelParser.fingerprint(a), ModelParser.fingerprint(b));
        Map<String, String> ga = new Codegen(a, "7").generateAll();
        Map<String, String> gb = new Codegen(b, "7").generateAll();
        assertEquals(ga.keySet(), gb.keySet());
        for (String k : ga.keySet()) {
            assertEquals(ga.get(k), gb.get(k), "artifact differs: " + k);
        }
    }

    @Test
    void generationIsRepeatable() {
        ChipModel m = ModelParser.parse(YAML_A);
        assertEquals(new Codegen(m, "1").generateAll(), new Codegen(m, "1").generateAll());
    }

    @Test
    void everyMaskAndWriteSequenceCarriesModelVersion() {
        ChipModel m = ModelParser.parse(YAML_A);
        String h = new Codegen(m, "42").generateAll().get("c/regmold_det_chip.h");
        for (String line : h.split("\n")) {
            if (line.startsWith("#define REGMOLD_STATUS") || line.contains("regmold_status_")) {
                assertTrue(h.contains("rev 42"), "trace missing near: " + line);
            }
        }
        assertTrue(h.contains("REGMOLD_MODEL_REVISION \"42\""));
        assertTrue(h.contains("trace: model det-chip rev 42"));
    }

    @Test
    void atomicWriteReplacesWholeSet(@TempDir Path dir) throws IOException {
        Map<String, String> v1 = Map.of("a/x.txt", "one", "b/y.txt", "uno");
        Map<String, String> v2 = Map.of("a/x.txt", "two", "b/y.txt", "dos");
        ArtifactWriter.writeAtomic(dir, v1);
        ArtifactWriter.writeAtomic(dir, v2);
        assertEquals("two", Files.readString(dir.resolve("a/x.txt")));
        assertEquals("dos", Files.readString(dir.resolve("b/y.txt")));
        assertNoTempFiles(dir);
    }

    @Test
    void failedStagingLeavesPreviousSetUntouchedAndNoTempFiles(@TempDir Path parent) throws IOException {
        Path dir = parent.resolve("out");
        ArtifactWriter.writeAtomic(dir, Map.of("keep.txt", "original"));
        // a file name beyond NAME_MAX makes staging fail mid-generation
        Map<String, String> bad = Map.of("keep.txt", "corrupted", "x".repeat(300), "boom");
        assertThrows(IOException.class, () -> ArtifactWriter.writeAtomic(dir, bad));
        assertEquals("original", Files.readString(dir.resolve("keep.txt")));
        assertNoTempFiles(parent);
    }

    private void assertNoTempFiles(Path dir) throws IOException {
        try (Stream<Path> s = Files.walk(dir)) {
            assertTrue(s.noneMatch(p -> {
                String n = p.getFileName().toString();
                return n.contains(".tmp-") || n.contains(".old-");
            }), "temp files left behind");
        }
    }
}
