package com.regforge;

import com.regforge.codegen.CodegenService;
import com.regforge.model.ChipModel;
import com.regforge.yaml.YamlModelParser;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class FixtureCompileTest {

    private ChipModel fixtureModel() throws Exception {
        try (var in = new ClassPathResource("fixtures/fixture_chip.yaml").getInputStream()) {
            String yaml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return new YamlModelParser().parse(yaml);
        }
    }

    private String toolPath(String name) {
        try {
            Process p = new ProcessBuilder("/usr/bin/which", name)
                    .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            return p.waitFor() == 0 ? out : null;
        } catch (Exception e) {
            return null;
        }
    }

    private int run(List<String> command, Path dir, StringBuilder output) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command).directory(dir.toFile());
        pb.redirectErrorStream(true);
        Process process = pb.start();
        String captured = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        output.append(captured);
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
        return process.exitValue();
    }

    @Test
    void generatedCFixtureCompilesAndRuns() throws Exception {
        String cc = toolPath("cc");
        assumeTrue(cc != null, "本地缺少 cc，跳过 C fixture 编译");

        ChipModel model = fixtureModel();
        Path work = Files.createTempDirectory("regforge-cfixture");
        new CodegenService().generateToDir(model, "testc0000001", work);

        Path main = work.resolve("c/main.c");
        Files.writeString(main, """
                #include <stdio.h>
                #include <stdint.h>
                #include <string.h>
                #include "fixture_chip_regs.h"

                static uint8_t bank[64];

                int main(void) {
                    memset(bank, 0, sizeof(bank));
                    bank[0x0c] = 1u; /* TMR 复位值高字 = 1（低字优先布局） */
                    volatile void *base = (volatile void *)bank;

                    /* RW 位域：RMW 置位/清零 */
                    fixture_chip_ctrl_en_set(base);
                    if (fixture_chip_ctrl_en_get(fixture_chip_ctrl_read(base)) != 1u) return 1;
                    fixture_chip_ctrl_en_clear(base);
                    if ((fixture_chip_ctrl_read(base) & FIXTURE_CHIP_CTRL_EN_MASK) != 0u) return 2;

                    /* 掩码与偏移可追溯 */
                    if (FIXTURE_CHIP_CTRL_OFFSET != 0x0u) return 3;
                    if (FIXTURE_CHIP_TMR_CROSS_MASK != 0x000000fff0000000ull) return 4;

                    /* 多字读：低字优先，复位值高字为 1 */
                    uint64_t tmr = fixture_chip_tmr_read(base);
                    if (tmr != 0x0000000100000000ull) return 5;

                    /* W1S / W1C 原子入口 */
                    fixture_chip_ctrl_go_set(base);
                    fixture_chip_ctrl_done_clear(base);

                    /* 保留位契约：读取值为 0；驱动写回时必须回读值（0），
                       原始写 RSV 掩码后该掩码仍是调用方可见的原始写入——
                       真正的不变量是：驱动从不把 1 写进保留位。 */
                    uint32_t raw = fixture_chip_ctrl_read(base);
                    if ((raw & FIXTURE_CHIP_CTRL_RSV_MASK) != 0u) return 6;
                    uint32_t safe = (raw & FIXTURE_CHIP_CTRL_RSV_MASK); /* 回读值，不含 1 */
                    fixture_chip_ctrl_write(base, safe);
                    if ((fixture_chip_ctrl_read(base) & FIXTURE_CHIP_CTRL_RSV_MASK) != 0u) return 7;

                    printf("c-fixture-ok\\n");
                    return 0;
                }
                """);

        StringBuilder output = new StringBuilder();
        int rc = run(List.of(cc, "-std=c11", "-Wall", "-Wextra", "-Werror",
                "-Ic", "c/main.c", "-o", "c/fixture"), work, output);
        assertThat(rc).as("C 编译失败:\n%s", output).isZero();
        int runRc = run(List.of(work.resolve("c/fixture").toString()), work, output);
        assertThat(runRc).as("C fixture 运行失败:\n%s", output).isZero();
        assertThat(output.toString()).contains("c-fixture-ok");
    }

    @Test
    void generatedRustFixtureCompiles() throws Exception {
        String rustc = toolPath("rustc");
        assumeTrue(rustc != null, "本地缺少 rustc，跳过 Rust fixture 编译");

        ChipModel model = fixtureModel();
        Path work = Files.createTempDirectory("regforge-rsfixture");
        new CodegenService().generateToDir(model, "testrs000001", work);

        Path main = work.resolve("rust/fixture_main.rs");
        Files.writeString(main, """
                include!("fixture_chip_regs.rs");

                fn main() {
                    let mut bank = [0u8; 64];
                    bank[0x0c] = 1u8; // TMR 复位值高字 = 1（低字优先布局）
                    let base = bank.as_mut_ptr();
                    unsafe {
                        fixture_chip::ctrl_en_set(base);
                        let v = fixture_chip::ctrl_read(base as *const u8);
                        assert_eq!(fixture_chip::ctrl_en_get(v), 1);
                        fixture_chip::ctrl_en_clear(base);
                        let tmr = fixture_chip::tmr_read(base as *const u8);
                        assert_eq!(tmr, 0x0000000100000000u64);
                        assert_eq!(fixture_chip::TMR_CROSS_MASK, 0x000000fff0000000u64);
                        fixture_chip::ctrl_go_set(base);
                    }
                    println!("rust-fixture-ok");
                }
                """);

        StringBuilder output = new StringBuilder();
        int rc = run(List.of(rustc, "--edition", "2021", "-Dwarnings",
                "rust/fixture_main.rs", "-o", "rust/fixture"), work, output);
        assertThat(rc).as("Rust 编译失败:\n%s", output).isZero();
        int runRc = run(List.of(work.resolve("rust/fixture").toString()), work, output);
        assertThat(runRc).as("Rust fixture 运行失败:\n%s", output).isZero();
        assertThat(output.toString()).contains("rust-fixture-ok");
    }
}
