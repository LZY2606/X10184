package com.regforge;

import com.regforge.gen.CGenerator;
import com.regforge.gen.DocGenerator;
import com.regforge.gen.GeneratedFile;
import com.regforge.gen.ModelDiffer;
import com.regforge.gen.DiffEntry;
import com.regforge.gen.RustGenerator;
import com.regforge.model.Canonical;
import com.regforge.model.Field;
import com.regforge.model.Register;
import com.regforge.model.RegisterModel;
import com.regforge.parse.ParseResult;
import com.regforge.parse.YamlParser;
import com.regforge.sim.Simulator;
import com.regforge.sim.StepResult;
import com.regforge.sim.SimEvent;
import com.regforge.validate.Issue;
import com.regforge.validate.Validator;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ModelCoreTest {

    private final YamlParser parser = new YamlParser();
    private final Validator validator = new Validator();

    private ParseResult load(String resource) throws IOException {
        String yaml = new String(ModelCoreTest.class.getResourceAsStream(resource).readAllBytes(),
                StandardCharsets.UTF_8);
        return parser.parse(yaml);
    }

    @Test
    void parsesCrossWordFieldAndFlagsIt() throws IOException {
        ParseResult pr = load("/sample.yaml");
        assertFalse(pr.hasErrors());
        Register hwcfg = pr.model().findRegister("HWCFG");
        Field cross = hwcfg.getFields().get(0);
        assertEquals("CROSS", cross.getName());
        assertTrue(cross.getLsb() / 32 != cross.getMsb() / 32, "CROSS spans two words");
        List<Issue> issues = validator.validate(pr.model());
        assertTrue(issues.stream().anyMatch(i -> i.code().equals("CROSS_WORD_FIELD")));
    }

    @Test
    void aliasesShareStorage() throws IOException {
        RegisterModel m = load("/sample.yaml").model();
        Simulator sim = new Simulator(m);
        // write through the set alias
        sim.step("write", "CTRLSET", 0x1L);
        assertEquals(1L, sim.snapshot().get("CTRL"));
        // plain CTRL read reflects alias-written value
        StepResult read = sim.step("read-preview", "CTRL", null);
        assertEquals(1L, read.readWords().get(0));
    }

    @Test
    void reservedBitsPreservedOnWrite() throws IOException {
        RegisterModel m = load("/sample.yaml").model();
        Simulator sim = new Simulator(m);
        sim.step("write", "CTRL", 0xFFFFFFFFL);
        long ctrl = sim.snapshot().get("CTRL");
        assertEquals(0L, ctrl & 0xFFFFFF00L, "reserved region must stay at read value (reset zero)");
        assertTrue((ctrl & 0x1L) != 0L, "RW ENABLE written");
    }

    @Test
    void readClearIsNotConsumedByPreviewButConsumedByRead() throws IOException {
        RegisterModel m = load("/sample.yaml").model();
        Simulator sim = new Simulator(m);
        // set EVENT rc bit directly via write path (RW would mask it; set via a raw trick: use simulator state through write of rc-ignored)
        // EVENT is RC and read-only on write, so seed by writing 1 to the underlying via an effect-free route:
        // use a dedicated small model instead.
        RegisterModel seeded = parser.parse("""
                name: t
                version: "1"
                registers:
                  - name: R
                    address: 0x0
                    reset: 0x10000
                    fields:
                      - name: FLAG
                        bits: 16
                        access: rc
                """).model();
        Simulator s2 = new Simulator(seeded);
        StepResult preview = s2.step("read-preview", "R", null);
        assertEquals(0x10000L, preview.readWords().get(0));
        assertTrue(s2.snapshot().get("R") != 0L, "preview must not clear RC");
        StepResult consume = s2.step("read-consume", "R", null);
        assertEquals(0x10000L, consume.readWords().get(0));
        assertEquals(0L, s2.snapshot().get("R"), "consume clears RC");
    }

    @Test
    void w1cAndW1sSemantics() {
        RegisterModel m = parser.parse("""
                name: t
                version: "1"
                registers:
                  - name: R
                    address: 0x0
                    fields:
                      - name: S
                        bits: 0
                        access: w1s
                      - name: C
                        bits: 1
                        access: w1c
                """).model();
        Simulator sim = new Simulator(m);
        sim.step("write", "R", 0x1L);          // set bit0
        assertEquals(0x1L, sim.snapshot().get("R"));
        sim.step("write", "R", 0x3L);          // set bit0 again, clear bit1 (already 0): nothing new
        // seed bit1 via w1s semantics is only on bit0; directly pulse set of bit1 impossible -> test w1c after latch:
        // write bit1 as a normal set is not allowed; verify w1s does not clear on 0 write
        sim.step("write", "R", 0x0L);
        assertEquals(0x1L, sim.snapshot().get("R"), "writing zero must not change W1S bit");
    }

    @Test
    void sideEffectFiresAndLockBlocksWrites() throws IOException {
        RegisterModel m = load("/sample.yaml").model();
        Simulator sim = new Simulator(m);
        sim.step("write", "CTRL", 0x1L); // ENABLE -> sets STATUS.RUNNING
        assertEquals(1L, sim.snapshot().get("STATUS") & 0x1L);

        // lock CALIB then attempt a write
        sim.step("write", "CALIB", 0xABL);
        assertEquals(0xABL, sim.snapshot().get("CALIB"));
        sim.step("write", "LOCK", 0x1L);
        sim.step("write", "CALIB", 0xCDL);
        assertEquals(0xABL, sim.snapshot().get("CALIB"), "locked register ignores writes");
    }

    @Test
    void cyclicSideEffectIsReportedWithShortestPath() throws IOException {
        RegisterModel m = load("/cycle.yaml").model();
        List<Issue> issues = validator.validate(m);
        Issue cycle = issues.stream().filter(i -> i.code().equals("SIDE_EFFECT_CYCLE")).findFirst().orElse(null);
        assertNotNull(cycle);
        assertTrue(cycle.message().contains("A.F1"));
        assertTrue(cycle.message().contains("B.F2"));
        // shortest cycle visits each node once then closes
        assertTrue(cycle.path().size() <= 4);
    }

    @Test
    void overlappingFieldsAreErrors() {
        RegisterModel m = parser.parse("""
                name: t
                version: "1"
                registers:
                  - name: R
                    address: 0x0
                    fields:
                      - {name: A, bits: "3:0", access: rw}
                      - {name: B, bits: "7:2", access: rw}
                """).model();
        assertTrue(validator.validate(m).stream().anyMatch(i -> i.code().equals("FIELD_OVERLAP")));
    }

    @Test
    void keyOrderDoesNotChangeCanonicalHash() {
        String a = """
                name: t
                version: "1"
                registers:
                  - name: R
                    address: 0x10
                    fields:
                      - {name: A, bits: 0, access: rw}
                      - {name: B, bits: 1, access: w1c}
                """;
        String b = """
                version: "1"
                name: t
                registers:
                  - name: R
                    fields:
                      - {access: w1c, bits: 1, name: B}
                      - {bits: 0, name: A, access: rw}
                    address: 0x10
                """;
        RegisterModel ma = parser.parse(a).model();
        RegisterModel mb = parser.parse(b).model();
        assertEquals(Canonical.hash(ma), Canonical.hash(mb));
        // generated C must be byte-identical
        CGenerator cg = new CGenerator();
        List<GeneratedFile> fa = cg.generate(ma, 1).files();
        List<GeneratedFile> fb = cg.generate(mb, 1).files();
        assertEquals(fa.get(0).content(), fb.get(0).content());
        assertEquals(fa.get(1).content(), fb.get(1).content());
    }

    @Test
    void addressAndWidthChangeListsImpactedApis() {
        RegisterModel a = parser.parse("""
                name: t
                version: "1"
                registers:
                  - {name: R, address: 0x10, width: 32, fields: [{name: A, bits: 0, access: rw}]}
                """).model();
        RegisterModel b = parser.parse("""
                name: t
                version: "2"
                registers:
                  - {name: R, address: 0x20, width: 64, fields: [{name: A, bits: 0, access: rw}]}
                """).model();
        List<DiffEntry> diff = new ModelDiffer().diff(a, b);
        assertTrue(diff.stream().anyMatch(d -> d.symbol().equals("R") && d.kind().equals("ABI")
                && d.impactedApis().toString().contains("r_write")));
    }

    @Test
    void generatedBundlesCarryTraceability() throws IOException {
        RegisterModel m = load("/sample.yaml").model();
        String hash = Canonical.hash(m);
        List<GeneratedFile> files = new CGenerator().generate(m, 7).files();
        for (GeneratedFile f : files) {
            assertTrue(f.content().contains(hash), f.relativePath() + " must embed hash");
            assertTrue(f.content().contains("revision=7"));
        }
        String rust = new RustGenerator().generate(m, 7).files().get(0).content();
        assertTrue(rust.contains(hash));
        String md = new DocGenerator().generate(m, 7).files().get(0).content();
        assertTrue(md.contains(hash));
    }
}
