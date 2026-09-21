package com.regmold.sim;

import java.util.List;

public record SimResult(boolean ok, String error, long value, List<SimEvent> events) {

    static SimResult fail(String error) {
        return new SimResult(false, error, 0L, List.of());
    }
}
