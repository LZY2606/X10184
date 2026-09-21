package com.regforge.model;

import java.util.List;

public record AddressSpace(String name, long base, List<RegisterDef> registers) {
}
