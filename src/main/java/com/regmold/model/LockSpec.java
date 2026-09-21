package com.regmold.model;

/** Writes to the owning register are blocked unless register.field == openValue. */
public record LockSpec(String register, String field, long openValue) {}
