package com.regmold.domain;

/** Field/register access policy. */
public enum Access {
    RW("rw"),
    RO("ro"),
    RC("rc"),      // read-clears
    W1C("w1c"),    // write-one-to-clear
    W1S("w1s"),    // write-one-to-set
    RSVD("rsvd");  // reserved / preserved

    private final String key;

    Access(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    public static Access fromKey(String key) {
        for (Access a : values()) {
            if (a.key.equalsIgnoreCase(key) || a.name().equalsIgnoreCase(key)) {
                return a;
            }
        }
        throw new IllegalArgumentException("unknown access policy: " + key);
    }
}
