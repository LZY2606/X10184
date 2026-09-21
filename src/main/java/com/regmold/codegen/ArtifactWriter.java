package com.regmold.codegen;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Atomic artifact replacement: the complete new file set is staged in a
 * sibling temp directory, then swapped with the target directory. A failure
 * leaves the previous file set untouched and cleans up every temp artifact.
 */
public final class ArtifactWriter {

    private ArtifactWriter() {}

    public static void writeAtomic(Path dir, Map<String, String> files) throws IOException {
        Path parent = dir.getParent() != null ? dir.getParent() : Path.of(".").toAbsolutePath();
        String token = UUID.randomUUID().toString();
        Path staging = parent.resolve(dir.getFileName() + ".tmp-" + token);
        Path backup = parent.resolve(dir.getFileName() + ".old-" + token);
        try {
            for (Map.Entry<String, String> e : files.entrySet()) {
                Path target = staging.resolve(e.getKey());
                Files.createDirectories(target.getParent());
                Files.writeString(target, e.getValue(), StandardCharsets.UTF_8);
            }
        } catch (IOException | RuntimeException e) {
            deleteRecursively(staging);
            throw e;
        }
        boolean movedAside = false;
        try {
            if (Files.exists(dir)) {
                Files.move(dir, backup);
                movedAside = true;
            }
            Files.move(staging, dir);
        } catch (IOException | RuntimeException e) {
            if (movedAside && !Files.exists(dir)) {
                Files.move(backup, dir);
            }
            deleteRecursively(staging);
            throw e;
        }
        deleteRecursively(backup);
    }

    private static void deleteRecursively(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (Stream<Path> s = Files.walk(p)) {
            for (Path x : s.sorted(Comparator.reverseOrder()).toList()) Files.delete(x);
        }
    }
}
