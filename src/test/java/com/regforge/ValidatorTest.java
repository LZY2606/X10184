package com.regforge;

import com.regforge.model.AccessMode;
import com.regforge.model.AddressSpace;
import com.regforge.model.ChipModel;
import com.regforge.model.FieldDef;
import com.regforge.model.RegisterDef;
import com.regforge.model.SideEffect;
import com.regforge.validate.Diagnostic;
import com.regforge.validate.Validator;
import com.regforge.yaml.YamlModelParser;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ValidatorTest {

    private final Validator validator = new Validator();
    private final YamlModelParser parser = new YamlModelParser();

    private String fixture(String name) throws Exception {
        try (var in = new ClassPathResource("fixtures/" + name).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private Diagnostic find(List<Diagnostic> diags, String code) {
        return diags.stream().filter(d -> d.code().equals(code)).findFirst().orElse(null);
    }

    @Test
    void aliasAtSameAddressWithDifferentWriteSemanticsIsAllowed() throws Exception {
        ChipModel model = parser.parse(fixture("fixture_chip.yaml"));
        List<Diagnostic> diags = validator.validate(model);
        assertThat(diags.stream().filter(d -> d.severity() == Diagnostic.Severity.ERROR))
                .isEmpty();
        Diagnostic info = find(diags, "ALIAS_SEMANTICS");
        assertThat(info).isNotNull();
        assertThat(info.message()).contains("CTRL_ALIAS").contains("MODE");
    }

    @Test
    void overlappingFieldsAreRejected() {
        ChipModel model = modelWith(reg("R", 0x00, 32,
                List.of(new FieldDef("A", 0, 2, AccessMode.RW, ""),
                        new FieldDef("B", 2, 4, AccessMode.RW, ""))));
        Diagnostic d = find(validator.validate(model), "FIELD_OVERLAP");
        assertThat(d).isNotNull();
        assertThat(d.path()).contains("A", "B");
    }

    @Test
    void sameAddressWithoutAliasOfIsCollision() {
        RegisterDef a = reg("A", 0x10, 32,
                List.of(new FieldDef("F", 0, 0, AccessMode.RW, "")));
        RegisterDef b = reg("B", 0x10, 32,
                List.of(new FieldDef("F", 0, 0, AccessMode.RW, "")));
        ChipModel model = modelWith(a, b);
        assertThat(find(validator.validate(model), "ADDRESS_COLLISION")).isNotNull();
    }

    @Test
    void contradictorySideEffectChainReportsShortestPath() {
        // A.F -> B.G -> A.F：最短环长度 2
        RegisterDef a = reg("A", 0x00, 32, List.of(
                new FieldDef("F", 0, 0, AccessMode.RW, "")));
        RegisterDef b = reg("B", 0x04, 32, List.of(
                new FieldDef("G", 0, 0, AccessMode.RW, "")));
        a = new RegisterDef(a.name(), a.address(), a.width(), a.reset(), a.wordOrder(),
                a.aliasOf(), a.lockedBy(), a.unlockValue(), a.description(), a.fields(),
                List.of(new SideEffect("F", "B", "G", SideEffect.EffectAction.SET, null, "")));
        b = new RegisterDef(b.name(), b.address(), b.width(), b.reset(), b.wordOrder(),
                b.aliasOf(), b.lockedBy(), b.unlockValue(), b.description(), b.fields(),
                List.of(new SideEffect("G", "A", "F", SideEffect.EffectAction.CLEAR, null, "")));
        Diagnostic d = find(validator.validate(modelWith(a, b)), "SIDE_EFFECT_CYCLE");
        assertThat(d).isNotNull();
        assertThat(d.message()).contains("A.F").contains("B.G").contains("长度 2");
        assertThat(d.path()).startsWith("A.F").endsWith("A.F");
    }

    @Test
    void conflictingEffectsFromSameTriggerAreRejected() {
        RegisterDef a = reg("A", 0x00, 32, List.of(
                new FieldDef("F", 0, 0, AccessMode.RW, "")));
        RegisterDef b = reg("B", 0x04, 32, List.of(
                new FieldDef("G", 0, 3, AccessMode.RW, "")));
        a = new RegisterDef(a.name(), a.address(), a.width(), a.reset(), a.wordOrder(),
                a.aliasOf(), a.lockedBy(), a.unlockValue(), a.description(), a.fields(),
                List.of(
                        new SideEffect("F", "B", "G", SideEffect.EffectAction.ASSIGN, 1L, ""),
                        new SideEffect("F", "B", "G", SideEffect.EffectAction.ASSIGN, 2L, "")));
        Diagnostic d = find(validator.validate(modelWith(a, b)), "SIDE_EFFECT_CONFLICT");
        assertThat(d).isNotNull();
        assertThat(d.message()).contains("A.F").contains("B.G");
    }

    @Test
    void longerCycleDoesNotShadowShortestOne() {
        // A.F->B.G->C.H->A.F 长度 3，另有 A.F->C.H->A.F 长度 2
        RegisterDef a = reg("A", 0x00, 32, List.of(new FieldDef("F", 0, 0, AccessMode.RW, "")));
        RegisterDef b = reg("B", 0x04, 32, List.of(new FieldDef("G", 0, 0, AccessMode.RW, "")));
        RegisterDef c = reg("C", 0x08, 32, List.of(new FieldDef("H", 0, 0, AccessMode.RW, "")));
        a = withEffects(a, List.of(
                new SideEffect("F", "B", "G", SideEffect.EffectAction.SET, null, ""),
                new SideEffect("F", "C", "H", SideEffect.EffectAction.SET, null, "")));
        b = withEffects(b, List.of(
                new SideEffect("G", "C", "H", SideEffect.EffectAction.SET, null, "")));
        c = withEffects(c, List.of(
                new SideEffect("H", "A", "F", SideEffect.EffectAction.SET, null, "")));
        Diagnostic d = find(validator.validate(modelWith(a, b, c)), "SIDE_EFFECT_CYCLE");
        assertThat(d).isNotNull();
        assertThat(d.message()).contains("长度 2");
        assertThat(d.path()).containsExactly("A.F", "C.H", "A.F");
    }

    @Test
    void missingLockTargetIsAnError() {
        RegisterDef r = new RegisterDef("R", 0x00, 32, 0, RegisterDef.WordOrder.LOW_FIRST,
                null, "NOPE.KEY", null, "",
                List.of(new FieldDef("F", 0, 0, AccessMode.RW, "")), List.of());
        assertThat(find(validator.validate(modelWith(r)), "LOCK_TARGET_MISSING")).isNotNull();
    }

    private static RegisterDef reg(String name, long addr, int width, List<FieldDef> fields) {
        return new RegisterDef(name, addr, width, 0, RegisterDef.WordOrder.LOW_FIRST,
                null, null, null, "", fields, List.of());
    }

    private static RegisterDef withEffects(RegisterDef reg, List<SideEffect> effects) {
        return new RegisterDef(reg.name(), reg.address(), reg.width(), reg.reset(),
                reg.wordOrder(), reg.aliasOf(), reg.lockedBy(), reg.unlockValue(),
                reg.description(), reg.fields(), effects);
    }

    private static ChipModel modelWith(RegisterDef... regs) {
        return new ChipModel("m", "little",
                List.of(new AddressSpace("S", 0, List.of(regs))));
    }
}
