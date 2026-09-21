package com.regforge.diff;

import java.util.List;

public record ModelDiff(
        String fromHash,
        String toHash,
        List<String> addedRegisters,
        List<String> removedRegisters,
        List<ApiChange> abiChanges,
        List<String> behaviorChanges) {

    public record ApiChange(String register, String kind, String detail, List<String> affectedApis) {
    }
}
