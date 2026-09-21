package com.regmold.domain;

import java.util.ArrayList;
import java.util.List;

public class EffectModel {
    public String name;
    public String description;
    public String triggerRegister;
    public long triggerMask;
    public EffectAction action = EffectAction.SET;
    public String targetRegister;
    public String targetField;
    public String sourceRegister;
    public String sourceField;
    public List<String> conflictPath = new ArrayList<>();
}
