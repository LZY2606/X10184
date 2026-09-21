package com.regforge.sim;

import java.util.List;

/** One observable thing that happened during a simulation step. */
public record SimEvent(String kind, String detail, List<String> trace) {
    public static SimEvent of(String kind, String detail, List<String> trace) {
        return new SimEvent(kind, detail, trace);
    }
}
