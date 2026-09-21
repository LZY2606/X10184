package com.regmold.gen;

import com.regmold.domain.Diagnostic;
import com.regmold.domain.Model;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

public class CodeGenerator {

    public GenResult generate(Model model, String semanticHash, List<Diagnostic> diagnostics) {
        GenResult result = new GenResult();
        result.semanticHash = semanticHash;
        new CGenerator().generate(result, model);
        new RustGenerator().generate(result, model);
        new DocGenerator().generate(result, model, diagnostics);
        result.files = new TreeMap<>(result.files);
        return result;
    }
}
