package com.regforge.model;

import java.util.List;
import java.util.Optional;

public record ChipModel(
        String name,
        String endianness,
        List<AddressSpace> addressSpaces) {

    public List<RegisterDef> allRegisters() {
        return addressSpaces.stream()
                .flatMap(s -> s.registers().stream().map(r -> (RegisterDef) r))
                .toList();
    }

    public Optional<RegisterDef> findRegister(String name) {
        return allRegisters().stream().filter(r -> r.name().equals(name)).findFirst();
    }

    public String qualifiedField(String register, String field) {
        return register + "." + field;
    }
}
