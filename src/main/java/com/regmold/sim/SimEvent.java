package com.regmold.sim;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SimEvent {
    public String kind;
    public String detail;
    public String effect;
    public Map<String, Long> changes = new LinkedHashMap<>();

    public SimEvent() {
    }

    public SimEvent(String kind, String detail) {
        this.kind = kind;
        this.detail = detail;
    }

    public SimEvent(String kind, String detail, String effect) {
        this.kind = kind;
        this.detail = detail;
        this.effect = effect;
    }
}
