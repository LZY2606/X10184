package com.regmold.domain;

import java.util.List;

/** A register-map model revision. */
public record Model(
        String name,
        String version,
        int addressBits,
        int wordBytes,
        Endianness endianness,
        List<Register> registers,
        List<Effect> effects,
        List<LockRule> locks,
        String sourceYaml,
        long revisionId,
        String canonicalYaml) {

    public Model withRevision(long id, String canonical) {
        return new Model(name, version, addressBits, wordBytes, endianness, registers, effects, locks,
                sourceYaml, id, canonical);
    }

    public Register register(String name) {
        for (Register r : registers) {
            if (r.name().equals(name)) {
                return r;
            }
        }
        return null;
    }

    public int wordBits() {
        return wordBytes * 8;
    }
}
