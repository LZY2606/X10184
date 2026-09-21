package com.regmold.model;

/** A write-triggered state change on another field. */
public record SideEffect(String targetRegister, String targetField, Action action) {
    public enum Action {
        SET, CLEAR, TOGGLE;

        public static Action fromString(String s) {
            return switch (s.trim().toLowerCase()) {
                case "set" -> SET;
                case "clear" -> CLEAR;
                case "toggle" -> TOGGLE;
                default -> throw new IllegalArgumentException("unknown side-effect action: " + s);
            };
        }
    }

    public String targetKey() {
        return targetRegister + "." + targetField;
    }
}
