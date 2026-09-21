package com.regmold.domain;

import java.util.ArrayList;
import java.util.List;

public class Model {
    public int schemaVersion = 1;
    public String name;
    public String description;
    public int dataWidth = 32;
    public String endianness = "little";
    public List<RegisterModel> registers = new ArrayList<>();
    public List<EffectModel> effects = new ArrayList<>();
}
