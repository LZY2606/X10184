package com.regforge.codegen;

import com.regforge.model.ChipModel;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 代码生成入口：
 * 1) 输出确定性 —— 同一模型（相同规范化语义）字节级一致；
 * 2) 原子替换（两阶段提交）——
 *    a. 所有内容先在内存渲染（渲染异常不触碰磁盘）；
 *    b. 全部写入暂存目录；
 *    c. 被替换的旧文件先 ATOMIC_MOVE 到暂存区备份；
 *    d. 新文件逐个 ATOMIC_MOVE 到目标；
 *    e. 任一步失败则回滚所有已替换文件，保证目标目录要么是旧版、要么是新版，
 *       不会留下半套文件。
 */
@Service
public class CodegenService {

    private final CGenerator cGenerator = new CGenerator();
    private final RustGenerator rustGenerator = new RustGenerator();

    public Map<String, String> renderAll(ChipModel model, String hash) {
        Map<String, String> merged = new TreeMap<>();
        merged.putAll(cGenerator.generate(model, hash));
        merged.putAll(rustGenerator.generate(model, hash));
        return new LinkedHashMap<>(merged);
    }

    public Map<String, String> render(ChipModel model, String hash, String lang) {
        return switch (lang.toLowerCase()) {
            case "c" -> cGenerator.generate(model, hash);
            case "rust", "rs" -> rustGenerator.generate(model, hash);
            default -> throw new IllegalArgumentException("不支持的生成物语言: " + lang);
        };
    }

    public void generateToDir(ChipModel model, String hash, Path outDir) throws IOException {
        // 阶段 0：内存渲染全部内容，任何异常都不会触碰目标目录。
        Map<String, String> files = renderAll(model, hash);

        Path parent = outDir.toAbsolutePath().getParent();
        if (parent == null) {
            throw new IOException("无法解析输出目录的父目录: " + outDir);
        }
        Files.createDirectories(parent);
        Path staging = Files.createTempDirectory(parent,
                ".regforge-gen-" + hash + "-");
        Path backupDir = staging.resolve(".backup");
        Files.createDirectories(backupDir);

        // 阶段 1：全部写入暂存目录。
        try {
            for (Map.Entry<String, String> entry : files.entrySet()) {
                Path target = staging.resolve(entry.getKey());
                Files.createDirectories(target.getParent());
                Files.writeString(target, entry.getValue(), StandardCharsets.UTF_8);
            }
        } catch (IOException | RuntimeException e) {
            deleteRecursively(staging);
            throw e;
        }

        // 阶段 2：备份将被覆盖的旧文件。
        List<Path> backedUp = new ArrayList<>();
        try {
            for (String relative : files.keySet()) {
                Path destination = outDir.resolve(relative);
                if (Files.isRegularFile(destination)) {
                    Path backup = backupDir.resolve(relative);
                    Files.createDirectories(backup.getParent());
                    moveAtomically(destination, backup);
                    backedUp.add(destination);
                }
            }
        } catch (IOException | RuntimeException e) {
            restoreBackup(backupDir, outDir, backedUp);
            deleteRecursively(staging);
            throw e;
        }

        // 阶段 3：新文件逐个原子落位；失败则回滚全部已落位文件。
        // existed 记录落位前目标文件是否已存在：回滚时新增文件必须删除，旧文件恢复备份。
        Map<String, Boolean> existed = new java.util.HashMap<>();
        for (String relative : files.keySet()) {
            existed.put(relative, backedUp.contains(outDir.resolve(relative)));
        }
        List<String> installed = new ArrayList<>();
        try {
            for (String relative : files.keySet()) {
                Path source = staging.resolve(relative);
                Path destination = outDir.resolve(relative);
                Files.createDirectories(destination.getParent());
                moveAtomically(source, destination);
                installed.add(relative);
            }
        } catch (IOException | RuntimeException e) {
            rollback(outDir, backupDir, installed, existed);
            deleteRecursively(staging);
            throw new IOException("生成过程中文件落位失败，已回滚到上一版本: " + e.getMessage(), e);
        }
        deleteRecursively(staging);
    }

    private void rollback(Path outDir, Path backupDir, List<String> installed,
                          Map<String, Boolean> existed) {
        // 先恢复备份的旧文件，再删除本次新增、失败前不存在的文件
        for (String relative : installed) {
            Path backup = backupDir.resolve(relative);
            if (Files.exists(backup)) {
                try {
                    Files.createDirectories(outDir.resolve(relative).getParent());
                    moveAtomically(backup, outDir.resolve(relative));
                } catch (IOException ignored) {
                    // 尽力回滚
                }
            } else if (!Boolean.TRUE.equals(existed.get(relative))) {
                try {
                    Files.deleteIfExists(outDir.resolve(relative));
                } catch (IOException ignored) {
                    // 尽力回滚
                }
            }
        }
    }

    private void restoreBackup(Path backupDir, Path outDir, List<Path> backedUp) {
        for (Path destination : backedUp) {
            String relative = outDir.relativize(destination).toString();
            Path backup = backupDir.resolve(relative);
            if (!Files.exists(backup)) {
                continue;
            }
            try {
                Files.createDirectories(destination.getParent());
                moveAtomically(backup, destination);
            } catch (IOException ignored) {
                // 尽力回滚
            }
        }
    }

    private void moveAtomically(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 临时目录清理失败不影响生成语义
                }
            });
        } catch (IOException ignored) {
            // 同上
        }
    }
}
