package com.regmold.diff;

import java.util.ArrayList;
import java.util.List;

public class DiffEntry {
    public String kind;
    public String scope;
    public String name;
    public String detail;
    public String from;
    public String to;
    public List<String> affectedApis = new ArrayList<>();
}
