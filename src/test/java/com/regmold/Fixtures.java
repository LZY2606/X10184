package com.regmold;

/** YAML fixtures shared by tests. */
public final class Fixtures {

    private Fixtures() {
    }

    public static final String BASE = """
            name: dev1
            version: "1.0"
            address_bits: 32
            word_bytes: 4
            endianness: le
            registers:
              - name: CTRL
                address: 0x4000
                width_bits: 32
                fields:
                  - {name: EN, bits: 0, access: rw}
                  - {name: UPDATE, bits: 3, access: w1s}
                  - {name: LOCK, bits: 4, access: ro}
              - name: CTRL_SET
                address: 0x4000
                width_bits: 32
                access: w1s
                alias_of: CTRL
              - name: CTRL_CLR
                address: 0x4000
                width_bits: 32
                access: w1c
                alias_of: CTRL
              - name: STATUS
                address: 0x4008
                width_bits: 32
                fields:
                  - {name: DONE, bits: 0, access: rc}
                  - {name: OVF, bits: 1, access: w1c}
                  - {name: KEEP, bits: [15, 8], access: rsvd, reset: 0xab}
              - name: PAIR
                address: 0x4010
                width_bits: 64
                read_order: low-first
                fields:
                  - {name: COUNT, bits: [47, 0], access: ro}
                  - {name: TAG, bits: [55, 48], access: rw}
            effects:
              - {trigger: CTRL.UPDATE, action: set, target: CTRL.LOCK}
            """;

    /** Same semantics as BASE but with mapping-style registers and shuffled keys. */
    public static final String BASE_SHUFFLED = """
            endianness: le
            word_bytes: 4
            address_bits: 32
            version: "1.0"
            name: dev1
            effects:
              - {action: set, target: CTRL.LOCK, trigger: CTRL.UPDATE}
            registers:
              STATUS:
                address: 0x4008
                width_bits: 32
                fields:
                  KEEP: {bits: [15, 8], access: rsvd, reset: 0xab}
                  OVF: {bits: 1, access: w1c}
                  DONE: {bits: 0, access: rc}
              PAIR:
                address: 0x4010
                width_bits: 64
                read_order: low-first
                fields:
                  TAG: {bits: [55, 48], access: rw}
                  COUNT: {bits: [47, 0], access: ro}
              CTRL_CLR:
                address: 0x4000
                width_bits: 32
                access: w1c
                alias_of: CTRL
              CTRL_SET:
                address: 0x4000
                width_bits: 32
                access: w1s
                alias_of: CTRL
              CTRL:
                address: 0x4000
                width_bits: 32
                fields:
                  LOCK: {bits: 4, access: ro}
                  UPDATE: {bits: 3, access: w1s}
                  EN: {bits: 0, access: rw}
            """;

    public static final String CYCLIC = """
            name: cyc
            version: "1"
            registers:
              - name: A
                address: 0x0
                width_bits: 8
                fields:
                  - {name: X, bits: 0, access: rw}
                  - {name: Y, bits: 1, access: rw}
                  - {name: Z, bits: 2, access: rw}
            effects:
              - {trigger: A.X, action: set, target: A.Y}
              - {trigger: A.Y, action: clear, target: A.Z}
              - {trigger: A.Z, action: set, target: A.X}
            """;

    public static final String CONTRADICTION = """
            name: con
            version: "1"
            registers:
              - name: A
                address: 0x0
                width_bits: 8
                fields:
                  - {name: X, bits: 0, access: rw}
                  - {name: Y, bits: 1, access: rw}
                  - {name: Z, bits: 2, access: rw}
            effects:
              - {trigger: A.X, action: set, target: A.Y}
              - {trigger: A.Y, action: set, target: A.Z}
              - {trigger: A.X, action: clear, target: A.Z}
            """;

    public static final String OVERLAP = """
            name: ov
            version: "1"
            registers:
              - name: R
                address: 0x0
                width_bits: 16
                fields:
                  - {name: F1, bits: [3, 0], access: rw}
                  - {name: F2, bits: [7, 2], access: rw}
            """;

    public static final String CROSSWORD_INVALID = """
            name: cw
            version: "1"
            word_bytes: 4
            registers:
              - name: P
                address: 0x0
                width_bits: 64
                fields:
                  - {name: SPLIT, bits: [40, 24], access: rw}
            """;
}
