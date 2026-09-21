package com.regforge.yaml;

public class ModelParseException extends RuntimeException {
    public ModelParseException(String message) {
        super(message);
    }

    public ModelParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
