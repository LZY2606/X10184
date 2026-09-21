package com.regmold.domain;

/**
 * A hardware side effect: when {@code trigger} is written (or one of its bits written
 * when the trigger is a field), {@code target} is forced to the given action.
 */
public record Effect(Ref trigger, EffectAction action, Ref target, String description) {

    public String edgeKey() {
        return action.name();
    }
}
