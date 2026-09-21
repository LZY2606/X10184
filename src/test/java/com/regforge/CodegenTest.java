package com.regforge;

import com.regforge.codegen.CGenerator;
import com.regforge.codegen.CodegenService;
import com.regforge.codegen.RustGenerator;
import com.regforge.model.ChipModel;
import com.regforge.yaml.YamlModelParser;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CodegenTest {

    private final YamlModelParser parser = new YamlModelParser();
    private final CGenerator cGenerator = new CGenerator();
    private final RustGenerator rustGenerator = new RustGenerator();

    private String fixture(String name) throws Exception {
        try (var in = new ClassPathResource("fixtures/" + name).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String firstFile(Map<String, String> files) {
        return files.values().iterator().next();
    }

    @Test
    void sameSemanticsDifferentKeyOrderProduceIdenticalFiles() throws Exception {
        ChipModel a = parser.parse(fixture("order_a.yaml"));
        ChipModel b = parser.parse(fixture("order_b.yaml"));
        String hash = parser.hashOf(a);
        assertThat(hash).isEqualTo(parser.hashOf(b));

        String ca = firstFile(cGenerator.generate(a, hash));
        String cb = firstFile(cGenerator.generate(b, hash));
        assertThat(ca).isEqualTo(cb);

        String ra = firstFile(rustGenerator.generate(a, hash));
        String rb = firstFile(rustGenerator.generate(b, hash));
        assertThat(ra).isEqualTo(rb);
    }

    @Test
    void everyMaskCarriesModelVersionForTraceability() throws Exception {
        ChipModel model = parser.parse(fixture("fixture_chip.yaml"));
        String hash = parser.hashOf(model);
        String header = firstFile(cGenerator.generate(model, hash));
        assertThat(header).contains("版本: " + hash);
        for (String line : header.split("\n")) {
            if (line.startsWith("#define") && line.contains("_MASK")) {
                int defIndex = header.indexOf(line);
                int prevComment = header.lastIndexOf("/*", defIndex);
                int commentEnd = header.indexOf("*/", prevComment);
                assertThat(header.substring(prevComment, commentEnd))
                        .as("掩码 %s 必须可追溯模型版本", line)
                        .contains("model " + hash);
            }
        }
        String rust = firstFile(rustGenerator.generate(model, hash));
        assertThat(rust).contains("版本: " + hash);
        assertThat(rust).contains("// model " + hash);
    }

    @Test
    void crossWordMaskSpansBothWords() throws Exception {
        ChipModel model = parser.parse(fixture("fixture_chip.yaml"));
        String rust = firstFile(rustGenerator.generate(model, parser.hashOf(model)));
        assertThat(rust).contains("TMR_CROSS_MASK: u64 = 0x000000fff0000000");
        String c = firstFile(cGenerator.generate(model, parser.hashOf(model)));
        assertThat(c).contains("FIXTURE_CHIP_TMR_CROSS_MASK  0x000000fff0000000u");
        assertThat(c).contains("低字优先");
    }

    @Test
    void failedGenerationLeavesPreviousFileSetIntact() throws Exception {
        ChipModel model = parser.parse(fixture("fixture_chip.yaml"));
        String oldHash = "oldversion01";
        Path work = Files.createTempDirectory("regforge-atomic");
        Path out = work.resolve("out");
        new CodegenService().generateToDir(model, oldHash, out);
        Map<String, String> oldFiles = readAll(out);
        assertThat(oldFiles).isNotEmpty();

        // 旧版是 fixture_chip；新版换一个模型（order_demo），生成物文件名不同。
        // 在新版第二个文件（rust/order_demo_regs.rs）的目标路径预置目录，
        // 使 c 头文件已经落位后、rust 文件落位失败，模拟两阶段提交中的中途失败。
        ChipModel other = parser.parse(fixture("order_a.yaml"));
        Path blocker = out.resolve("rust/order_demo_regs.rs");
        Files.createDirectories(blocker);

        assertThatThrownBy(() -> new CodegenService().generateToDir(other, "newhash0001", out))
                .isInstanceOf(IOException.class);

        // 回滚：已落位的新增 c 头文件必须被删除
        assertThat(Files.exists(out.resolve("c/order_demo_regs.h"))).isFalse();
        // 移除测试注入的阻塞物后，目录与旧版逐字节一致，没有任何半套文件
        Files.delete(blocker);
        assertThat(readAll(out)).isEqualTo(oldFiles);
        // 临时暂存目录必须被清理
        try (var entries = Files.list(work)) {
            assertThat(entries.noneMatch(p -> p.getFileName().toString()
                    .startsWith(".regforge-gen-"))).isTrue();
        }
        try (var walk = Files.walk(work)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            });
        }
    }

    private Map<String, String> readAll(Path root) throws IOException {
        Map<String, String> content = new java.util.TreeMap<>();
        try (var paths = Files.walk(root)) {
            paths.filter(Files::isRegularFile).forEach(p -> {
                try {
                    content.put(root.relativize(p).toString(),
                            Files.readString(p, StandardCharsets.UTF_8));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
        return content;
    }
}
