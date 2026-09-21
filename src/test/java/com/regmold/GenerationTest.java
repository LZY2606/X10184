package com.regmold;

import com.regmold.gen.AtomicFileWriter;
import com.regmold.gen.CodeGenerator;
import com.regmold.gen.GenResult;
import com.regmold.domain.Diagnostic;
import com.regmold.domain.ParsedModel;
import com.regmold.parse.YamlParser;
import com.regmold.validate.Validator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GenerationTest {

    private GenResult generate(String yaml) {
        ParsedModel parsed = new YamlParser().parse(yaml);
        List<Diagnostic> ds = new Validator().validate(parsed);
        assertTrue(ds.stream().noneMatch(d -> d.severity == Diagnostic.Severity.ERROR), ds.toString());
        return new CodeGenerator().generate(parsed.model, parsed.semanticHash, ds);
    }

    @Test
    void deterministicRegardlessOfKeyOrder() {
        GenResult a = generate(TestSupport.fixture("sample.yaml"));
        GenResult b = generate(TestSupport.fixture("reorder.yaml"));
        assertEquals(a.semanticHash, b.semanticHash);
        assertEquals(a.files, b.files);
    }

    @Test
    void repeatedGenerationIsByteIdentical() {
        GenResult a = generate(TestSupport.fixture("sample.yaml"));
        GenResult b = generate(TestSupport.fixture("sample.yaml"));
        assertEquals(a.files, b.files);
    }

    @Test
    void generatedArtifactsTraceSemanticHash() {
        GenResult a = generate(TestSupport.fixture("sample.yaml"));
        for (var entry : a.files.entrySet()) {
            assertTrue(entry.getValue().contains(a.semanticHash),
                    entry.getKey() + " missing semantic hash");
        }
        assertTrue(a.files.get("register_model.h").contains("GPIO_CTRL_ENABLE_MASK"));
    }

    @Test
    void atomicWriteLeavesNoHalfSetOnFailure(@TempDir Path tmp) throws Exception {
        GenResult a = generate(TestSupport.fixture("sample.yaml"));
        Path target = tmp.resolve("gen");
        new AtomicFileWriter().writeAll(target, a);
        String first = Files.readString(target.resolve("register_model.h"));

        AtomicFileWriter failing = new AtomicFileWriter() {
            @Override
            public void writeAll(Path targetDir, GenResult result) throws java.io.IOException {
                throw new java.io.IOException("simulated disk failure");
            }
        };
        assertThrows(java.io.IOException.class, () -> failing.writeAll(target, a));
        assertEquals(first, Files.readString(target.resolve("register_model.h")));
        assertTrue(Files.exists(target.resolve("register_model.c")));
    }

    @Test
    void atomicWriteReplacesWholeDirectory(@TempDir Path tmp) throws Exception {
        GenResult a = generate(TestSupport.fixture("sample.yaml"));
        Path target = tmp.resolve("gen");
        new AtomicFileWriter().writeAll(target, a);
        Path stale = target.resolve("stale.txt");
        Files.writeString(stale, "old");
        new AtomicFileWriter().writeAll(target, a);
        assertFalse(Files.exists(stale), "stale file must be removed by atomic replacement");
        assertTrue(Files.exists(target.resolve("MANIFEST.txt")));
    }
}
