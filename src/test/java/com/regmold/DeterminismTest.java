package com.regmold;

import com.regmold.domain.Model;
import com.regmold.gen.CodeGenerator;
import com.regmold.io.ModelCanonicalizer;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DeterminismTest {

    @Test
    void sameSemanticsDifferentKeyOrderYieldIdenticalCanonicalAndOutputs() {
        Model a = TestModels.parse(Fixtures.BASE);
        Model b = TestModels.parse(Fixtures.BASE_SHUFFLED);
        assertEquals(a.canonicalYaml(), b.canonicalYaml());
        assertEquals(ModelCanonicalizer.hash(a.canonicalYaml()), ModelCanonicalizer.hash(b.canonicalYaml()));

        CodeGenerator.Generated ga = CodeGenerator.build(a);
        CodeGenerator.Generated gb = CodeGenerator.build(b);
        assertEquals(fileHash(ga), fileHash(gb));
        assertEquals(ga.files().size(), gb.files().size());
        for (int i = 0; i < ga.files().size(); i++) {
            assertEquals(ga.files().get(i).relativePath(), gb.files().get(i).relativePath());
            assertEquals(ga.files().get(i).content(), gb.files().get(i).content());
        }
    }

    private static String fileHash(CodeGenerator.Generated g) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (var f : g.files()) {
                md.update(f.relativePath().getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0);
                md.update(f.content().getBytes(StandardCharsets.UTF_8));
            }
            StringBuilder sb = new StringBuilder();
            for (byte bx : md.digest()) {
                sb.append(String.format("%02x", bx));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
