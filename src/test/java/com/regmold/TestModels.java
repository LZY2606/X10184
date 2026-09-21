package com.regmold;

import com.regmold.domain.Model;
import com.regmold.io.ModelCanonicalizer;
import com.regmold.io.YamlModelParser;

public final class TestModels {

    private TestModels() {
    }

    public static Model parse(String yaml) {
        Model parsed = YamlModelParser.parse(yaml);
        Model normalized = ModelCanonicalizer.normalize(parsed);
        return normalized.withRevision(1, ModelCanonicalizer.canonicalYaml(normalized));
    }
}
