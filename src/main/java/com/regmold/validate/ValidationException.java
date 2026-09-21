package com.regmold.validate;

import java.util.List;

public class ValidationException extends RuntimeException {

    private final List<ValidationIssue> issues;

    public ValidationException(List<ValidationIssue> issues) {
        super(issues.stream().map(i -> i.code() + ": " + i.message()).reduce((a, b) -> a + "; " + b).orElse("validation failed"));
        this.issues = List.copyOf(issues);
    }

    public List<ValidationIssue> issues() {
        return issues;
    }
}
