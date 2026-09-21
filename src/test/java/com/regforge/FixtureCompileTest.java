package com.regforge;

import com.regforge.gen.CGenerator;
import com.regforge.gen.GeneratedFile;
import com.regforge.gen.RustGenerator;
import com.regforge.model.RegisterModel;
import com.regforge.parse.YamlParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Generates the minimal C and Rust fixtures from a model and compiles them
 * with the local toolchain (clang + rustc).  Skipped automatically if the
 * toolchain is missing.
 */
@DisabledOnOs(OS.WINDOWS)
class FixtureCompileTest {

    private final String yaml = """
            name: fixture_dev
            version: "1.0"
            address_space: {bits: 32, word_bits: 32, endian: little}
            registers:
              - name: CTRL
                address: 0x0
                width: 32
                set_alias: CTRLSET
                clr_alias: CTRLCLR
                fields:
                  - {name: EN, bits: 0, access: rw}
                  - {name: RSVD, bits: "31:1", access: reserved}
              - name: CTRLSET
                address: 0x4
                width: 32
                alias_of: CTRL
              - name: CTRLCLR
                address: 0x8
                width: 32
                alias_of: CTRL
              - name: PAIR
                address: 0x10
                width: 64
                read_order: low_first
                fields:
                  - {name: WIDE, bits: "40:24", access: rw}
                  - {name: RSVD, bits: "63:41", access: reserved}
            """;

    @Test
    void compilesCFixture(@TempDir Path tmp) throws Exception {
        Path clang = which("clang");
        if (clang == null) return; // toolchain absent -> skip
        RegisterModel m = new YamlParser().parse(yaml).model();
        for (GeneratedFile f : new CGenerator().generate(m, 1).files()) {
            Path target = tmp.resolve(f.relativePath());
            Files.createDirectories(target.getParent());
            Files.writeString(target, f.content());
        }
        Path main = tmp.resolve("c/main.c");
        Files.writeString(main, """
                #include <stdint.h>
                #include "regs.h"
                static uint64_t mem[16];
                uint64_t fixture_dev_bus_read(uint64_t a) { return mem[a/4]; }
                void fixture_dev_bus_write(uint64_t a, uint64_t d) { mem[a/4] = d; }
                int main(void) {
                    fixture_dev_ctrl_write(1);
                    if (fixture_dev_ctrl_read() != 1) return 1;
                    fixture_dev_ctrl_atomic_set(2);
                    fixture_dev_pair_write(0);
                    uint64_t p = fixture_dev_pair_read();
                    (void)p;
                    return 0;
                }
                """);
        int rc = run(List.of(clang.toString(), "-std=c11", "-Wall", "-Wextra", "-Werror",
                "-I" + tmp.resolve("c"),
                main.toString(), tmp.resolve("c/regs.c").toString(),
                "-o", tmp.resolve("cfixture").toString()), tmp);
        assertEquals(0, rc, "C fixture must compile warning-free");
        int run = run(List.of(tmp.resolve("cfixture").toString()), tmp);
        assertEquals(0, run, "C fixture must run");
    }

    @Test
    void compilesRustFixture(@TempDir Path tmp) throws Exception {
        Path rustc = which("rustc");
        if (rustc == null) return;
        RegisterModel m = new YamlParser().parse(yaml).model();
        GeneratedFile rs = new RustGenerator().generate(m, 1).files().get(0);
        Path lib = tmp.resolve("regs.rs");
        Files.writeString(lib, rs.content());
        int rc = run(List.of(rustc.toString(), "--edition", "2021", "--crate-type", "lib",
                lib.toString(), "-o", tmp.resolve("librregs.rlib").toString()), tmp);
        assertEquals(0, rc, "Rust fixture must compile");

        // also compile a tiny binary that links the module to exercise the API
        Path bin = tmp.resolve("bin.rs");
        Files.writeString(bin, """
                #[allow(unused)]
                mod regs { include!(%s); }
                struct Mem(Vec<u64>);
                impl regs::Bus for Mem {
                    fn read(&mut self, addr: u64) -> u64 { self.0[(addr/4) as usize] }
                    fn write(&mut self, addr: u64, d: u64) { self.0[(addr/4) as usize] = d; }
                }
                fn main() {
                    let mut bus = Mem(vec![0u64; 16]);
                    regs::ctrl::write(&mut bus, 1);
                    assert_eq!(regs::ctrl::read(&mut bus), 1);
                    let _ = regs::pair::read(&mut bus);
                }
                """.formatted(quote(lib)));
        int rc2 = run(List.of(rustc.toString(), "--edition", "2021", bin.toString(),
                "-o", tmp.resolve("rsfixture").toString()), tmp);
        assertEquals(0, rc2, "Rust fixture binary must compile");
        int runRc = run(List.of(tmp.resolve("rsfixture").toString()), tmp);
        assertEquals(0, runRc, "Rust fixture must run");
    }

    private static String quote(Path p) {
        return "\"" + p.toString().replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private Path which(String tool) {
        for (String dir : System.getenv().getOrDefault("PATH", "").split(":")) {
            Path p = Path.of(dir, tool);
            if (Files.isExecutable(p)) return p;
        }
        return null;
    }

    private int run(List<String> cmd, Path cwd) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(cwd.toFile()).redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        boolean finished = p.waitFor(60, TimeUnit.SECONDS);
        if (!finished) { p.destroyForcibly(); fail("command timed out: " + cmd); }
        if (p.exitValue() != 0) {
            throw new AssertionError("command failed: " + cmd + "\n" + out);
        }
        return p.exitValue();
    }
}
