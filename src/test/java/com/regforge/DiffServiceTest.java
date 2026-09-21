package com.regforge;

import com.regforge.diff.DiffService;
import com.regforge.diff.ModelDiff;
import com.regforge.model.AccessMode;
import com.regforge.model.AddressSpace;
import com.regforge.model.ChipModel;
import com.regforge.model.FieldDef;
import com.regforge.model.RegisterDef;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DiffServiceTest {

    private static RegisterDef reg(long address, int width, List<FieldDef> fields) {
        return new RegisterDef("CTRL", address, width, 0, RegisterDef.WordOrder.LOW_FIRST,
                null, null, null, "", fields, List.of());
    }

    private static ChipModel model(RegisterDef register, String endianness) {
        return new ChipModel("demo", endianness,
                List.of(new AddressSpace("APB", 0, List.of(register))));
    }

    @Test
    void addressChangeListsGeneratedApisInsteadOfSilentRename() {
        ChipModel before = model(reg(0x00, 32,
                List.of(new FieldDef("EN", 0, 0, AccessMode.RW, ""))), "little");
        ChipModel after = model(reg(0x10, 32,
                List.of(new FieldDef("EN", 0, 0, AccessMode.RW, ""))), "little");
        ModelDiff diff = new DiffService().diff(before, after, "aaa", "bbb");
        ModelDiff.ApiChange change = diff.abiChanges().stream()
                .filter(c -> c.kind().equals("ADDRESS")).findFirst().orElseThrow();
        assertThat(change.affectedApis()).contains(
                "DEMO_CTRL_OFFSET", "demo_ctrl_read", "demo_ctrl_write");
    }

    @Test
    void widthAndEndiannessChangesAreAbiChanges() {
        ChipModel before = model(reg(0x00, 32,
                List.of(new FieldDef("EN", 0, 0, AccessMode.RW, ""))), "little");
        ChipModel after = model(reg(0x00, 64,
                List.of(new FieldDef("EN", 0, 0, AccessMode.RW, ""))), "big");
        ModelDiff diff = new DiffService().diff(before, after, "aaa", "bbb");
        assertThat(diff.abiChanges()).anyMatch(c -> c.kind().equals("WIDTH"));
        assertThat(diff.abiChanges()).anyMatch(c -> c.kind().equals("ENDIANNESS")
                && c.affectedApis().contains("demo_ctrl_read"));
    }

    @Test
    void accessModeChangeIsBehaviouralAndTouchesSetClearApis() {
        ChipModel before = model(reg(0x00, 32,
                List.of(new FieldDef("EN", 0, 0, AccessMode.RW, ""))), "little");
        ChipModel after = model(reg(0x00, 32,
                List.of(new FieldDef("EN", 0, 0, AccessMode.W1C, ""))), "little");
        ModelDiff diff = new DiffService().diff(before, after, "aaa", "bbb");
        ModelDiff.ApiChange change = diff.abiChanges().stream()
                .filter(c -> c.kind().equals("FIELD_ACCESS")).findFirst().orElseThrow();
        assertThat(change.affectedApis()).contains(
                "demo_ctrl_en_set", "demo_ctrl_en_clear");
        assertThat(diff.behaviorChanges())
                .anyMatch(b -> b.contains("RW") && b.contains("W1C"));
    }
}
