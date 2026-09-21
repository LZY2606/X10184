package com.regforge.parse;

import com.regforge.model.RegisterModel;
import com.regforge.validate.Issue;

import java.util.List;

public record ParseResult(RegisterModel model, List<Issue> issues) {
    public boolean hasErrors() {
        return issues.stream().anyMatch(i -> i.severity() == Issue.Severity.ERROR);
    }
}
