package com.regforge.model;

import java.util.ArrayList;
import java.util.List;

/**
 * A register, possibly multi-word.  widthBits may exceed wordBits; fields may
 * cross word boundaries.  Alias registers share an address with a different
 * name and write semantics.
 */
public class Register {
    private String name;
    private String description = "";
    private long address;
    private int widthBits = 32;
    private long reset = 0L;
    /** aliasOf names another register whose storage this register shares. */
    private String aliasOf;
    /** Word (bus) read ordering for multi-word registers: low_first (LE) or high_first (BE). */
    private String readOrder = "low_first";
    private String lockedBy;
    private String lockLevel = "1";
    /** Optional atomic entry: writes to SET_ADDR set mask bits, CLR_ADDR clears them. */
    private String setAlias;
    private String clrAlias;
    private final List<Field> fields = new ArrayList<>();

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description == null ? "" : description; }
    public long getAddress() { return address; }
    public void setAddress(long address) { this.address = address; }
    public int getWidthBits() { return widthBits; }
    public void setWidthBits(int widthBits) { this.widthBits = widthBits; }
    public long getReset() { return reset; }
    public void setReset(long reset) { this.reset = reset; }
    public String getAliasOf() { return aliasOf; }
    public void setAliasOf(String aliasOf) { this.aliasOf = aliasOf; }
    public String getReadOrder() { return readOrder; }
    public void setReadOrder(String readOrder) { this.readOrder = readOrder; }
    public String getLockedBy() { return lockedBy; }
    public void setLockedBy(String lockedBy) { this.lockedBy = lockedBy; }
    public String getLockLevel() { return lockLevel; }
    public void setLockLevel(String lockLevel) { this.lockLevel = lockLevel; }
    public String getSetAlias() { return setAlias; }
    public void setSetAlias(String setAlias) { this.setAlias = setAlias; }
    public String getClrAlias() { return clrAlias; }
    public void setClrAlias(String clrAlias) { this.clrAlias = clrAlias; }
    public List<Field> getFields() { return fields; }

    public boolean isAlias() { return aliasOf != null && !aliasOf.isBlank(); }
    public boolean isMultiWord(int wordBits) { return widthBits > wordBits; }
    public int wordCount(int wordBits) { return Math.max(1, (widthBits + wordBits - 1) / wordBits); }
    public boolean highFirst() { return "high_first".equalsIgnoreCase(readOrder); }
}
