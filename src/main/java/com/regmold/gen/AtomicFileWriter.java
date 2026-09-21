package com.regmold.gen;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public class AtomicFileWriter {

    public void writeAll(Path targetDir, GenResult result) throws IOException {
        Path parent = targetDir.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path staging = Files.createTempDirectory(parent, "regmold-stage-");
        Path backup = null;
        boolean committed = false;
        try {
            List<String> manifest = new ArrayList<>();
            for (Map.Entry<String, String> entry : result.files.entrySet()) {
                Path out = staging.resolve(entry.getKey());
                if (out.getParent() != null) {
                    Files.createDirectories(out.getParent());
                }
                Files.writeString(out, entry.getValue(), StandardCharsets.UTF_8);
                manifest.add(entry.getKey());
            }
            manifest.sort(Comparator.naturalOrder());
            manifest.add(0, "semantic-hash=" + result.semanticHash);
            Files.writeString(staging.resolve("MANIFEST.txt"), String.join("\n", manifest) + "\n",
                    StandardCharsets.UTF_8);

            if (Files.exists(targetDir)) {
                backup = Files.createTempDirectory(parent, "regmold-backup-");
                copyDir(targetDir, backup);
                deleteRecursively(targetDir);
            }
            Files.move(staging, targetDir, StandardCopyOption.ATOMIC_MOVE);
            committed = true;
        } finally {
            if (!committed) {
                safeDelete(staging);
            }
        }
        if (backup != null) {
            if (committed) {
                safeDelete(backup);
            } else {
                try {
                    if (!Files.exists(targetDir)) {
                        Files.move(backup, targetDir, StandardCopyOption.ATOMIC_MOVE);
                    }
                } catch (IOException ignored) {
                    // restore is best-effort only when a rollback is attempted
                }
            }
        }
    }

    private void copyDir(Path src, Path dst) throws IOException {
        try (var stream = Files.walk(src)) {
            for (Path p : stream.sorted(Comparator.naturalOrder()).toList()) {
                Path rel = src.relativize(p);
                Path target = dst.resolve(rel.toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    if (target.getParent() != null) {
                        Files.createDirectories(target.getParent());
                    }
                    Files.copy(p, target, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }

    private void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (var stream = Files.walk(path)) {
            for (Path p : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    private void safeDelete(Path path) {
        try {
            deleteRecursively(path);
        } catch (IOException ignored) {
            // best effort cleanup of staging directory
        }
    }
}
