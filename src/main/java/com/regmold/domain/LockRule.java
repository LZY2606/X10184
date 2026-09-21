package com.regmold.domain;

/** Writes to {@code target} are blocked while {@code master} is set (level-sensitive). */
public record LockRule(Ref target, Ref master) {
}
