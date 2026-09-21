package com.regforge.model;

import java.util.ArrayList;
import java.util.List;

/** A contiguous bit field that may span multiple register words. */
public class Field {
    private String name;
    private String description = "";
    private int lsb;
    private int msb;
    private FieldAccess access = FieldAccess.RW;
    private long reset = 0L;
    private final List<FieldEffect> effects = new ArrayList<>();

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description == null ? "" : description; }
    public int getLsb() { return lsb; }
    public void setLsb(int lsb) { this.lsb = lsb; }
    public int getMsb() { return msb; }
    public void setMsb(int msb) { this.msb = msb; }
    public FieldAccess getAccess() { return access; }
    public void setAccess(FieldAccess access) { this.access = access; }
    public long getReset() { return reset; }
    public void setReset(long reset) { this.reset = reset; }
    public List<FieldEffect> getEffects() { return effects; }

    public int width() { return msb - lsb + 1; }
    public long mask() {
        if (width() >= 64) return ~0L;
        return ((1L << width()) - 1L) << lsb;
    }
}
