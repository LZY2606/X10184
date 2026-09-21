package com.regmold.model;

public record SideEffect(String triggerReg, String triggerField, long triggerValue,
                         String targetReg, String targetField, long targetValue) {
    public String describe() {
        return triggerReg + "." + triggerField + "==" + triggerValue
                + " -> " + targetReg + "." + targetField + ":=" + targetValue;
    }
}
