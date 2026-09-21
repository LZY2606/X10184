package com.regmold;

import com.regmold.gen.CodeGenerator;
import com.regmold.gen.GenResult;
import com.regmold.domain.Diagnostic;
import com.regmold.domain.ParsedModel;
import com.regmold.parse.YamlParser;
import com.regmold.validate.Validator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class FixtureCompileTest {

    private final String cHarness = """
            #include "register_model.h"
            #include <stdio.h>
            #include <stdint.h>
            static uint64_t storage[16];
            static uint64_t rd(void *ctx, uint64_t off, unsigned word) {
                (void)ctx;
                uint64_t v = storage[(off / 4) + word];
                storage[(off / 4) + word] &= ~1u; /* DONE is read-clear */
                return v;
            }
            static uint64_t pk(void *ctx, uint64_t off, unsigned word) {
                (void)ctx; return storage[(off / 4) + word];
            }
            static void wr(void *ctx, uint64_t off, unsigned word, uint64_t v) {
                (void)ctx; storage[(off / 4) + word] = v;
            }
            int main(void) {
                rm_bus_t bus = {0, rd, pk, wr};
                gpio_ctrl_write(&bus, 1);
                if ((gpio_ctrl_read(&bus) & 1u) != 1u) return 1;
                gpio_ctrl_write(&bus, 0);
                if ((gpio_ctrl_peek(&bus) & 1u) != 0u) return 2;
                storage[1] = 3;
                if (gpio_status_peek(&bus) != 3u) return 3;
                if (gpio_status_read(&bus) != 3u) return 4;
                if (gpio_status_read(&bus) != 2u) return 5;
                if (gpio_status_done_get(0xFFFFFFFFu) != 1u) return 6;
                return 0;
            }
            """;

    private final String rustHarness = """
            include!("./register_model.rs");
            struct Mem { cells: [u64; 16] }
            impl Bus for Mem {
                fn read(&mut self, offset: u64, word: u32) -> u64 {
                    let i = ((offset / 4) as usize) + word as usize;
                    let v = self.cells[i];
                    self.cells[i] &= !1u64;
                    v
                }
                fn peek(&mut self, offset: u64, word: u32) -> u64 {
                    self.cells[((offset / 4) as usize) + word as usize]
                }
                fn write(&mut self, offset: u64, word: u32, value: u64) {
                    self.cells[((offset / 4) as usize) + word as usize] = value;
                }
            }
            fn main() {
                let mut m = Mem { cells: [0u64; 16] };
                ctrl::write(&mut m, 1);
                assert_eq!(ctrl::read(&mut m) & 1, 1);
                ctrl::write(&mut m, 0);
                assert_eq!(ctrl::peek(&mut m) & 1, 0);
                m.cells[1] = 3;
                assert_eq!(status::peek(&mut m), 3);
                assert_eq!(status::read(&mut m), 3);
                assert_eq!(status::read(&mut m), 2);
                assert_eq!(status::done::get(0xFFFF_FFFF), 1);
            }
            """;

    static boolean toolchainReady() {
        return available("clang") && available("rustc");
    }

    private static boolean available(String cmd) {
        try {
            Process p = new ProcessBuilder(cmd, "--version").redirectErrorStream(true).start();
            p.waitFor(5, TimeUnit.SECONDS);
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private GenResult generate() {
        ParsedModel parsed = new YamlParser().parse(TestSupport.fixture("sample.yaml"));
        List<Diagnostic> ds = new Validator().validate(parsed);
        assertTrue(ds.stream().noneMatch(d -> d.severity == Diagnostic.Severity.ERROR));
        return new CodeGenerator().generate(parsed.model, parsed.semanticHash, ds);
    }

    @Test
    @EnabledIf("toolchainReady")
    void compilesAndRunsCFixture(@TempDir Path tmp) throws Exception {
        GenResult gen = generate();
        Files.writeString(tmp.resolve("register_model.h"), gen.files.get("register_model.h"));
        Files.writeString(tmp.resolve("register_model.c"), gen.files.get("register_model.c"));
        Files.writeString(tmp.resolve("harness.c"), cHarness);
        run(tmp, "clang", "-std=c11", "-Wall", "-Wextra", "-Werror",
                "harness.c", "register_model.c", "-o", "harness_c");
        assertEquals(0, run(tmp, "./harness_c"));
    }

    @Test
    @EnabledIf("toolchainReady")
    void compilesAndRunsRustFixture(@TempDir Path tmp) throws Exception {
        GenResult gen = generate();
        Files.writeString(tmp.resolve("register_model.rs"), gen.files.get("register_model.rs"));
        Files.writeString(tmp.resolve("harness.rs"), rustHarness);
        run(tmp, "rustc", "--edition", "2021", "-Dwarnings", "harness.rs", "-o", "harness_rs");
        assertEquals(0, run(tmp, "./harness_rs"));
    }

    private int run(Path dir, String... cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile());
        Process p = pb.start();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        p.getInputStream().transferTo(out);
        p.getErrorStream().transferTo(out);
        int rc = p.waitFor(60, TimeUnit.SECONDS) ? p.exitValue() : -1;
        if (rc != 0) {
            fail(String.join(" ", cmd) + " failed (" + rc + "):\n" + out.toString(StandardCharsets.UTF_8));
        }
        return rc;
    }
}
