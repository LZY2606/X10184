package com.regforge.model;

/**
 * A state change triggered when the owning field is written with a 1.
 *
 * @param action set | clear | latch
 * @param target fully-qualified target "reg.field" (field-level) or "reg" (whole register)
 */
public record FieldEffect(String action, String target) {

    public String normalizedAction() {
        return action == null ? "" : action.trim().toLowerCase();
    }

    public boolean isLatch() {
        return "latch".equals(normalizedAction());
    }

    /** For latch effects the target is copied (snapshotted) into the owning field. */
    public String display() {
        return normalizedAction() + " " + target;
    }
}
