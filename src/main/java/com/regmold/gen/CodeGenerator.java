package com.regmold.gen;

import com.regmold.domain.Model;
import com.regmold.io.ModelCanonicalizer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Builds all artifacts in memory from a validated model, then installs them atomically. */
public final class CodeGenerator {

    private CodeGenerator() {
    }

    public record Generated(String hash, List<GenFile> files, List<SymbolInfo> symbols) {
    }

    public static Generated build(Model model) {
        String hash = ModelCanonicalizer.hash(model.canonicalYaml());
        List<GenFile> files = new ArrayList<>();
        files.addAll(CGenerator.generate(model, hash));
        files.addAll(RustGenerator.generate(model, hash));
        files.addAll(DocGenerator.generate(model, hash));
        files.sort(Comparator.comparing(GenFile::relativePath));
        List<SymbolInfo> symbols = SymbolInventory.symbols(model);
        files.add(ManifestGenerator.generate(model, hash, files, symbols));
        return new Generated(hash, List.copyOf(files), symbols);
    }

    /**
     * Builds the complete new tree in a sibling staging directory and swaps it into place
     * with a single directory-level atomic move. The previous tree (if any) is moved to a
     * backup and removed only after the swap succeeds; if the swap fails the backup is
     * restored, so a failed generation never leaves a half set of files.
     */
    public static List<Path> installAtomic(Generated generated, Path targetDir) throws IOException {
        Path normalized = targetDir.toAbsolutePath().normalize();
        Path parent = normalized.getParent() == null ? Path.of(".") : normalized.getParent();
        Path staging = parent.resolve(normalized.getFileName() + ".staging-"
                + Long.toHexString(System.nanoTime()));
        Path backup = parent.resolve(normalized.getFileName() + ".backup-"
                + Long.toHexString(System.nanoTime()));
        List<Path> written = new ArrayList<>();
        boolean hadExisting = Files.exists(normalized);
        try {
            Files.createDirectories(staging);
            for (GenFile f : generated.files()) {
                Path out = staging.resolve(f.relativePath());
                Files.createDirectories(out.getParent());
                Files.writeString(out, f.content(), StandardCharsets.UTF_8);
            }
            if (hadExisting) {
                Files.move(normalized, backup, StandardCopyOption.ATOMIC_MOVE);
            }
            Files.move(staging, normalized, StandardCopyOption.ATOMIC_MOVE);
            deleteQuietly(backup);
        } catch (IOException e) {
            deleteQuietly(staging);
            if (hadExisting && Files.exists(backup) && !Files.exists(normalized)) {
                try {
                    Files.move(backup, normalized, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException restoreFailure) {
                    e.addSuppressed(restoreFailure);
                }
            }
            throw e;
        }
        for (GenFile f : generated.files()) {
            written.add(normalized.resolve(f.relativePath()));
        }
        deleteQuietly(staging);
        return List.copyOf(written);
    }


    private static void deleteQuietly(Path p) {
        if (!Files.exists(p)) {
            return;
        }
        try (var walk = Files.walk(p)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best effort
                }
            });
        } catch (IOException ignored) {
            // best effort
        }
    }
}
