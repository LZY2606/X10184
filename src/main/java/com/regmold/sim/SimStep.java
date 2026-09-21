package com.regmold.sim;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SimStep {
    public int index;
    public String type;
    public String register;
    public Integer word;
    public long written;
    public long value;
    public boolean blocked;
    public List<SimEvent> events = new ArrayList<>();
    public Map<String, String> state = new LinkedHashMap<>();
    public String note;
}
