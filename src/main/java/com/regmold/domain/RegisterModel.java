package com.regmold.domain;

import java.util.ArrayList;
import java.util.List;

public class RegisterModel {
    public String name;
    public String description;
    public long offset;
    public Integer width;
    public Long reset;
    public String endianness;
    public String aliasOf;
    public String aliasWrite;
    public boolean noClearRead;
    public LockModel lock;
    public List<FieldModel> fields = new ArrayList<>();

    public int effectiveWidth(int dataWidth) {
        return width != null ? width : dataWidth;
    }
}
