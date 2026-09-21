package com.regmold.io;

import java.util.ArrayList;
import java.util.List;

public class YamlParseException extends RuntimeException {

    private final List<String> errors;

    public YamlParseException(List<String> errors) {
        super(String.join("; ", errors));
        this.errors = List.copyOf(errors);
    }

    public YamlParseException(String message) {
        this(new ArrayList<>(List.of(message)));
    }

    public List<String> errors() {
        return errors;
    }
}
