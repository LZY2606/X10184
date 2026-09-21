package com.regforge;

import com.regforge.model.ChipModel;
import com.regforge.model.RegisterDef;
import com.regforge.yaml.YamlModelParser;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class YamlModelParserTest {

    private final YamlModelParser parser = new YamlModelParser();

    private String fixture(String name) throws Exception {
        try (var in = new ClassPathResource("fixtures/" + name).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void parsesCrossWordFieldAcross32BitBoundary() throws Exception {
        ChipModel model = parser.parse(fixture("fixture_chip.yaml"));
        RegisterDef tmr = model.findRegister("TMR").orElseThrow();
        assertThat(tmr.width()).isEqualTo(64);
        assertThat(tmr.wordOrder()).isEqualTo(RegisterDef.WordOrder.LOW_FIRST);
        var cross = tmr.field("CROSS");
        assertThat(cross.lsb()).isEqualTo(28);
        assertThat(cross.msb()).isEqualTo(39);
        assertThat(cross.mask()).isEqualTo(0x000000FF_F0000000L);
    }

    @Test
    void keyOrderDoesNotChangeCanonicalFormOrHash() throws Exception {
        ChipModel a = parser.parse(fixture("order_a.yaml"));
        ChipModel b = parser.parse(fixture("order_b.yaml"));
        assertThat(parser.canonicalize(a)).isEqualTo(parser.canonicalize(b));
        assertThat(parser.hashOf(a)).isEqualTo(parser.hashOf(b));
    }

    @Test
    void canonicalFormIsStableAcrossReinvocation() throws Exception {
        ChipModel model = parser.parse(fixture("order_a.yaml"));
        String first = parser.canonicalize(model);
        String second = parser.canonicalize(parser.parse(first));
        assertThat(second).isEqualTo(first);
    }

    @Test
    void hexLiteralsAndUnderscoreFreeWideResetAccepted() throws Exception {
        ChipModel model = parser.parse(fixture("fixture_chip.yaml"));
        RegisterDef tmr = model.findRegister("TMR").orElseThrow();
        assertThat(tmr.reset()).isEqualTo(0x1_0000_0000L);
    }
}
