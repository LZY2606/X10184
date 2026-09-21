package com.regforge.model;

public record SideEffect(
        String triggerField,
        String targetRegister,
        String targetField,
        EffectAction action,
        Long value,
        String description) {

    public enum EffectAction {
        SET,
        CLEAR,
        TOGGLE,
        ASSIGN;

        public static EffectAction from(String raw) {
            return switch (raw.trim().toUpperCase()) {
                case "SET" -> SET;
                case "CLEAR", "CLR" -> CLEAR;
                case "TOGGLE", "TGL" -> TOGGLE;
                case "ASSIGN", "WRITE", "=" -> ASSIGN;
                default -> throw new IllegalArgumentException("未知副作用动作: " + raw);
            };
        }

        public String symbol() {
            return switch (this) {
                case SET -> "置位";
                case CLEAR -> "清零";
                case TOGGLE -> "翻转";
                case ASSIGN -> "赋值";
            };
        }
    }

    public String node() {
        return targetRegister + "." + targetField;
    }
}
