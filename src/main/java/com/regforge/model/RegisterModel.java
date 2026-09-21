package com.regforge.model;

import java.util.ArrayList;
import java.util.List;

/** Parsed register model (one YAML document / one revision). */
public class RegisterModel {
    private String name = "device";
    private String description = "";
    private String version = "0.0.0";
    private int addressBits = 32;
    private int wordBits = 32;
    private String endian = "little";
    private final List<Register> registers = new ArrayList<>();

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description == null ? "" : description; }
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    public int getAddressBits() { return addressBits; }
    public void setAddressBits(int addressBits) { this.addressBits = addressBits; }
    public int getWordBits() { return wordBits; }
    public void setWordBits(int wordBits) { this.wordBits = wordBits; }
    public String getEndian() { return endian; }
    public void setEndian(String endian) { this.endian = endian; }
    public List<Register> getRegisters() { return registers; }

    public Register findRegister(String name) {
        for (Register r : registers) {
            if (r.getName().equals(name)) return r;
        }
        return null;
    }

    public long wordMask() {
        if (wordBits >= 64) return ~0L;
        return (1L << wordBits) - 1L;
    }
}
