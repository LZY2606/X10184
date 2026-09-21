package com.regforge.sim;

import java.util.List;

/** Result of one simulation step. */
public record StepResult(int step, String type, String target, long valueWritten,
                         List<Long> readWords, List<SimEvent> events, java.util.Map<String, Long> registers) {
}
