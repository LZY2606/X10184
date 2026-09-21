package com.regmold.domain;

import java.util.ArrayList;
import java.util.List;

public class Diagnostic {
    public enum Severity { ERROR, WARNING, INFO }

    public Severity severity;
    public String code;
    public String message;
    public String register;
    public String field;
    public List<String> path = new ArrayList<>();
    public String effect;

    public Diagnostic() {
    }

    public Diagnostic(Severity severity, String code, String message) {
        this.severity = severity;
        this.code = code;
        this.message = message;
    }

    public static Diagnostic error(String code, String message) {
        return new Diagnostic(Severity.ERROR, code, message);
    }

    public static Diagnostic warning(String code, String message) {
        return new Diagnostic(Severity.WARNING, code, message);
    }

    public Diagnostic at(String register, String field) {
        this.register = register;
        this.field = field;
        return this;
    }

    public Diagnostic withPath(List<String> p) {
        this.path = new ArrayList<>(p);
        return this;
    }
}
