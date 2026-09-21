package com.regmold;

import com.regmold.gen.CodeGenerator;
import com.regmold.domain.Model;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AtomicInstallTest {

    @Test
    void installsAllFiles(@TempDir Path tmp) throws IOException {
        Path target = tmp.resolve("out");
        CodeGenerator.Generated g = CodeGenerator.build(TestModels.parse(Fixtures.BASE));
        List<Path> paths = CodeGenerator.installAtomic(g, target);
        assertEquals(g.files().size(), paths.size());
        for (var f : g.files()) {
            Path p = target.resolve(f.relativePath());
            assertTrue(Files.exists(p), p.toString());
            assertEquals(f.content(), Files.readString(p, StandardCharsets.UTF_8));
        }
        // no staging/backup leftovers
        try (var list = Files.list(tmp)) {
            list.forEach(p -> assertTrue(p.getFileName().toString().equals("out"),
                    "leftover: " + p));
        }
    }

    @Test
    void failureLeavesPreviousTreeIntact() throws IOException {
        Path tmp = Files.createTempDirectory("regmold-atomic");
        Path target = tmp.resolve("out");
        CodeGenerator.Generated first = CodeGenerator.build(TestModels.parse(Fixtures.BASE));
        CodeGenerator.installAtomic(first, target);
        Path marker = target.resolve("c/inc/dev1_regs.h");
        String before = Files.readString(marker, StandardCharsets.UTF_8);

        // Simulate a mid-generation write failure with an invalid (NUL-containing) path name.
        CodeGenerator.Generated broken = new CodeGenerator.Generated("deadbeef", List.of(
                new com.regmold.gen.GenFile("c/inc/x.h", "ok"),
                new com.regmold.gen.GenFile("bad\u0000name.txt", "no")), List.of());
        IOException thrown = null;
        try {
            CodeGenerator.installAtomic(broken, target);
        } catch (IOException e) {
            thrown = e;
        }
        assertTrue(thrown != null);
        // previous tree restored exactly
        assertTrue(Files.exists(marker));
        assertEquals(before, Files.readString(marker, StandardCharsets.UTF_8));
        assertFalse(Files.exists(target.resolve("c/inc/x.h")));
        // no staging leftovers remain
        try (var list = Files.list(tmp)) {
            list.forEach(x -> assertTrue(x.getFileName().toString().equals("out"),
                    "leftover after failure: " + x));
        }
    }
}
