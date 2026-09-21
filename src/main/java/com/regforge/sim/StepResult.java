package com.regforge.sim;

import java.util.List;
import java.util.Map;

public record StepResult(
        int index,
        SimOp op,
        boolean accepted,
        String readValue,
        List<String> readWords,
        List<String> log,
        Map<String, String> stateAfter) {
}
