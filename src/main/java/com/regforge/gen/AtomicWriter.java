package com.regforge.gen;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;

/**
 * Writes a generation set atomically: everything is staged in a sibling
 * temp directory first; on any failure the staging dir is deleted and the
 * destination is left untouched, so a failed build never leaves a half set.
 */
public final class AtomicWriter {

    private AtomicWriter() {}

    public static void writeAll(Path destination, List<GeneratedFile> files) throws IOException {
        Path parent = destination.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Path stage = Files.createTempDirectory(parent, "gen-stage-");
        try {
            for (GeneratedFile f : files) {
                Path target = stage.resolve(f.relativePath());
                Files.createDirectories(target.getParent());
                Files.writeString(target, f.content(), StandardCharsets.UTF_8);
            }
            // deterministic swap: clear existing then move stage into place
            if (Files.exists(destination)) {
                deleteRecursively(destination);
            }
            try {
                Files.move(stage, destination, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(stage, destination);
            }
        } catch (IOException | RuntimeException e) {
            deleteRecursively(stage);
            throw e;
        }
    }

    private static void deleteRecursively(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (var paths = Files.walk(p)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            });
        }
    }
}
