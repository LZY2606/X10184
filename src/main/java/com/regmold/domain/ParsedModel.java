package com.regmold.domain;

import java.util.ArrayList;
import java.util.List;

public class ParsedModel {
    public Model model;
    public String canonicalYaml;
    public String semanticHash;
    public List<Diagnostic> diagnostics = new ArrayList<>();

    public ParsedModel() {
    }

    public ParsedModel(Model model) {
        this.model = model;
    }

    public boolean valid() {
        return diagnostics.stream().noneMatch(d -> d.severity == Diagnostic.Severity.ERROR);
    }
}
