package com.regmold.sim;

import java.util.LinkedHashMap;
import java.util.Map;

/** One observable state transition during a simulation step. */
public record SimEvent(String type, String detail, String target, Long before, Long after) {

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("detail", detail);
        m.put("target", target);
        if (before != null) {
            m.put("before", "0x" + Long.toUnsignedString(before, 16));
        }
        if (after != null) {
            m.put("after", "0x" + Long.toUnsignedString(after, 16));
        }
        return m;
    }
}
