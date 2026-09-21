package com.regmold;

import com.regmold.domain.Model;
import com.regmold.gen.CodeGenerator;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Generates the access layer from the fixture model and compiles+runs a minimal
 *  consumer binary entirely on the local toolchain. */
@DisabledOnOs(OS.WINDOWS)
class FixtureCompileTest {

    private static final String C_FIXTURE = """
            #include <stdint.h>
            #include <stdio.h>
            #include <string.h>
            #include "dev1_regs.h"

            static uint64_t mem[64];
            static uint64_t to_off(uint64_t addr) { return (addr - 0x4000u) / 4u; }

            uint32_t dev1_bus_read32(uint64_t addr) { return (uint32_t)mem[to_off(addr)]; }
            void dev1_bus_write32(uint64_t addr, uint32_t data) { mem[to_off(addr)] = data; }
            uint64_t dev1_bus_read64(uint64_t addr) {
                uint64_t lo = mem[to_off(addr)];
                uint64_t hi = mem[to_off(addr) + 1];
                return lo | (hi << 32);
            }
            void dev1_bus_write64(uint64_t addr, uint64_t data) {
                mem[to_off(addr)] = (uint32_t)data;
                mem[to_off(addr) + 1] = (uint32_t)(data >> 32);
            }

            int main(void) {
                /* reserved bits KEEP=0xab in STATUS */
                dev1_status_write(0xffffffffu);
                if ((dev1_status_read() & 0xff00u) != 0xab00u) { printf("reserved mismatch\\n"); return 1; }

                /* W1S/W1C alias ports: generated set/clear route through CTRL_SET/CTRL_CLR */
                dev1_ctrl_set(DEV1_CTRL_EN_SHIFT); /* set bit 0 via RMW fallback path */
                if ((dev1_ctrl_read() & 0x1u) != 0x1u) { printf("set failed\\n"); return 2; }
                dev1_ctrl_clear(0x1u);
                if ((dev1_ctrl_read() & 0x1u) != 0x0u) { printf("clear failed\\n"); return 3; }

                /* mask/shift constants exist and carry the model hash */
                if (DEV1_STATUS_KEEP_SHIFT != 8) { printf("shift wrong\\n"); return 4; }
                if (strlen(DEV1_MODEL_HASH) != 64) { printf("hash missing\\n"); return 5; }
                printf("C_FIXTURE_OK %s\\n", DEV1_MODEL_HASH);
                return 0;
            }
            """;

    private static final String RUST_FIXTURE = """
            mod dev1_regs;
            use dev1_regs::BusHooks;

            struct FakeBus { mem: std::cell::RefCell<[u64; 64]> }
            impl FakeBus {
                fn off(addr: u64) -> usize { ((addr - 0x4000) / 4) as usize }
            }
            impl BusHooks for FakeBus {
                fn read32(&self, addr: u64) -> u32 { self.mem.borrow()[Self::off(addr)] as u32 }
                fn write32(&self, addr: u64, data: u32) { self.mem.borrow_mut()[Self::off(addr)] = data as u64; }
                fn read64(&self, addr: u64) -> u64 {
                    let lo = self.mem.borrow()[Self::off(addr)];
                    let hi = self.mem.borrow()[Self::off(addr) + 1];
                    (lo & 0xffff_ffff) | (hi << 32)
                }
                fn write64(&self, addr: u64, data: u64) {
                    let mut m = self.mem.borrow_mut();
                    m[Self::off(addr)] = data & 0xffff_ffff;
                    m[Self::off(addr) + 1] = data >> 32;
                }
            }

            fn main() {
                let bus = FakeBus { mem: std::cell::RefCell::new([0u64; 64]) };
                dev1_regs::status::write(&bus, 0xffff_ffff);
                let v = dev1_regs::status::read(&bus);
                assert_eq!(v & 0xff00, 0xab00, "reserved mismatch");
                dev1_regs::ctrl::set(&bus, dev1_regs::ctrl::en::MASK);
                assert_eq!(dev1_regs::ctrl::read(&bus) & 0x1, 0x1);
                dev1_regs::ctrl::clear(&bus, dev1_regs::ctrl::en::MASK);
                assert_eq!(dev1_regs::ctrl::read(&bus) & 0x1, 0x0);
                assert_eq!(dev1_regs::status::keep::SHIFT, 8);
                assert_eq!(dev1_regs::MODEL_HASH.len(), 64);
                println!("RUST_FIXTURE_OK {}", dev1_regs::MODEL_HASH);
            }
            """;

    @Test
    void compilesAndRunsCFixture(@TempDir Path tmp) throws Exception {
        assumeCommand("cc");
        Model model = TestModels.parse(Fixtures.BASE);
        CodeGenerator.Generated gen = CodeGenerator.build(model);
        for (var f : gen.files()) {
            if (f.relativePath().startsWith("c/")) {
                Path p = tmp.resolve(f.relativePath());
                Files.createDirectories(p.getParent());
                Files.writeString(p, f.content(), StandardCharsets.UTF_8);
            }
        }
        Path main = tmp.resolve("fixture.c");
        Files.writeString(main, C_FIXTURE, StandardCharsets.UTF_8);
        Path bin = tmp.resolve("fixture_c");
        run(List.of("cc", "-std=c11", "-Wall", "-Wextra", "-Werror",
                "-I", tmp.resolve("c/inc").toString(),
                main.toString(), tmp.resolve("c/src/dev1_regs.c").toString(),
                "-o", bin.toString()), tmp);
        String out = run(List.of(bin.toString()), tmp);
        assertTrue(out.contains("C_FIXTURE_OK"), out);
    }

    @Test
    void compilesAndRunsRustFixture(@TempDir Path tmp) throws Exception {
        assumeCommand("rustc");
        Model model = TestModels.parse(Fixtures.BASE);
        CodeGenerator.Generated gen = CodeGenerator.build(model);
        for (var f : gen.files()) {
            if (f.relativePath().startsWith("rust/")) {
                Path p = tmp.resolve(f.relativePath());
                Files.createDirectories(p.getParent());
                Files.writeString(p, f.content(), StandardCharsets.UTF_8);
            }
        }
        Path main = tmp.resolve("fixture.rs");
        Files.writeString(main, RUST_FIXTURE, StandardCharsets.UTF_8);
        Path bin = tmp.resolve("fixture_rs");
        run(List.of("rustc", "--edition", "2021", "-Dwarnings",
                main.toString(), "-o", bin.toString()), tmp);
        String out = run(List.of(bin.toString()), tmp);
        assertTrue(out.contains("RUST_FIXTURE_OK"), out);
    }

    private static void assumeCommand(String cmd) {
        try {
            new ProcessBuilder("which", cmd).redirectErrorStream(true).start().waitFor();
        } catch (IOException | InterruptedException e) {
            throw new org.opentest4j.TestAbortedException("tool missing: " + cmd, e);
        }
    }

    private static String run(List<String> command, Path cwd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command).directory(cwd.toFile());
        Process p = pb.start();
        String stdout = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        boolean finished = p.waitFor(60, TimeUnit.SECONDS);
        if (!finished) {
            p.destroyForcibly();
            throw new IllegalStateException("command timed out: " + command);
        }
        if (p.exitValue() != 0) {
            throw new IllegalStateException("command failed " + command + "\n" + stdout + "\n" + stderr);
        }
        return stdout + stderr;
    }
}
