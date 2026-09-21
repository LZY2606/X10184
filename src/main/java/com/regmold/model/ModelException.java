package com.regmold.model;

import java.util.List;

public class ModelException extends RuntimeException {
    private final List<String> errors;

    public ModelException(List<String> errors) {
        super(String.join("; ", errors));
        this.errors = List.copyOf(errors);
    }

    public ModelException(String message) {
        this(List.of(message));
    }

    public List<String> errors() {
        return errors;
    }
}
