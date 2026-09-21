package com.regmold;

import com.regmold.codegen.Codegen;
import com.regmold.model.ChipModel;
import com.regmold.yaml.ModelParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Compiles the generated minimal C and Rust fixtures with the local toolchains. */
class FixtureCompileTest {

    static final String MODEL = """
        name: fixture-chip
        registers:
          - name: STATUS
            address: 0x00
            width: 32
            reset: 0x2
            fields:
              - { name: ERR, offset: 1, width: 1, access: w1c }
              - { name: COUNT, offset: 8, width: 8, access: rc }
              - { name: RSVD, offset: 16, width: 4, access: reserved }
          - name: CTRL
            address: 0x04
            width: 32
            fields:
              - { name: ENABLE, offset: 0, width: 1, access: rw }
          - name: COUNTER64
            address: 0x08
            width: 64
            wordOrder: hi-first
            fields:
              - { name: VALUE, offset: 28, width: 12, access: rw }
          - name: GPIO
            address: 0x10
            width: 32
            fields:
              - { name: PIN, offset: 0, width: 8, access: rw }
          - { name: GPIO_SET, address: 0x10, width: 32, aliasOf: GPIO, semantic: w1s }
        """;

    @TempDir Path dir;

    private Map<String, String> generate() {
        ChipModel m = ModelParser.parse(MODEL);
        return new Codegen(m, "9").generateAll();
    }

    @Test
    void generatedCCompilesAndRuns() throws Exception {
        Map<String, String> files = generate();
        Path cdir = dir.resolve("c");
        Files.createDirectories(cdir);
        for (var e : files.entrySet()) {
            if (e.getKey().startsWith("c/")) Files.writeString(cdir.resolve(Path.of(e.getKey()).getFileName().toString()), e.getValue());
        }
        Files.writeString(cdir.resolve("main.c"), """
            #include "regmold_fixture_chip.h"
            #include <stdio.h>
            static volatile uint32_t hw[16];
            int main(void) {
                uintptr_t base = (uintptr_t)hw;
                regmold_ctrl_enable_modify(base, 1u);
                regmold_status_err_clear(base);
                regmold_gpio_set_write(base, 0x5u);
                regmold_counter64_value_modify(base, 0xABCu);
                uint32_t s = regmold_status_read(base);
                uint64_t c = regmold_counter64_read(base);
                if (REGMOLD_STATUS_ERR_MASK != 0x2ull) return 1;
                if (regmold_gpio_read(base) != 0x5u) return 2;
                if (((c >> 28) & 0xFFFu) != 0xABCu) return 3;
                printf("c fixture ok rev=%s status=0x%x\\n", regmold_model_revision, s);
                return 0;
            }
            """);
        Path bin = dir.resolve("fixture_c");
        run(dir, "cc", "-std=c11", "-Wall", "-Wextra", "-Werror",
                cdir.resolve("regmold_fixture_chip.c").toString(),
                cdir.resolve("main.c").toString(), "-o", bin.toString());
        run(dir, bin.toString());
    }

    @Test
    void generatedRustCompilesAndRuns() throws Exception {
        Map<String, String> files = generate();
        String rs = files.get("rust/regmold_fixture_chip.rs");
        Files.writeString(dir.resolve("regmold_fixture_chip.rs"), rs);
        Files.writeString(dir.resolve("main.rs"), """
            mod regmold_fixture_chip;
            fn main() {
                let mut hw = [0u32; 16];
                let base = hw.as_mut_ptr();
                unsafe {
                    regmold_fixture_chip::ctrl_enable_modify(base, 1);
                    regmold_fixture_chip::status_err_clear(base);
                    regmold_fixture_chip::gpio_set_write(base, 0x5);
                    regmold_fixture_chip::counter64_value_modify(base, 0xABC);
                    let s = regmold_fixture_chip::status_read(base);
                    let c = regmold_fixture_chip::counter64_read(base);
                    assert_eq!(regmold_fixture_chip::STATUS_ERR_MASK, 0x2);
                    assert_eq!(regmold_fixture_chip::gpio_read(base), 0x5);
                    assert_eq!((c >> 28) & 0xFFF, 0xABC);
                    println!("rust fixture ok rev={} status=0x{:x}",
                             regmold_fixture_chip::REGMOLD_MODEL_REVISION, s);
                }
            }
            """);
        Path bin = dir.resolve("fixture_rust");
        run(dir, "rustc", "--edition", "2021", "-D", "warnings",
                dir.resolve("main.rs").toString(), "-o", bin.toString());
        run(dir, bin.toString());
    }

    private void run(Path workdir, String... cmd) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd).directory(workdir.toFile())
                .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        int code = p.waitFor();
        assertEquals(0, code, () -> String.join(" ", cmd) + "\n" + out);
    }
}
