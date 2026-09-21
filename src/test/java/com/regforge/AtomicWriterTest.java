package com.regforge;

import com.regforge.gen.AtomicWriter;
import com.regforge.gen.GeneratedFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AtomicWriterTest {

    @Test
    void writesWholeSet(@TempDir Path tmp) throws IOException {
        Path dest = tmp.resolve("out");
        List<GeneratedFile> files = List.of(
                new GeneratedFile("a/x.txt", "aaa"),
                new GeneratedFile("b/y.txt", "bbb"));
        AtomicWriter.writeAll(dest, files);
        assertEquals("aaa", Files.readString(dest.resolve("a/x.txt")));
        assertEquals("bbb", Files.readString(dest.resolve("b/y.txt")));
    }

    @Test
    void failureLeavesPreviousSetIntact(@TempDir Path tmp) throws IOException {
        Path dest = tmp.resolve("out");
        AtomicWriter.writeAll(dest, List.of(new GeneratedFile("keep.txt", "v1")));

        // an invalid path forces staging to fail after some files were written
        List<GeneratedFile> bad = List.of(
                new GeneratedFile("ok.txt", "x"),
                new GeneratedFile("sub/../../escape.txt", "y"));
        assertThrows(IOException.class, () -> AtomicWriter.writeAll(dest, bad));

        // destination untouched: still v1, no half set
        assertEquals("v1", Files.readString(dest.resolve("keep.txt")));
        assertFalse(Files.exists(dest.resolve("ok.txt")));
        // no staging dirs left behind
        try (var entries = Files.list(tmp)) {
            assertTrue(entries.noneMatch(p -> p.getFileName().toString().startsWith("gen-stage-")));
        }
    }
}
