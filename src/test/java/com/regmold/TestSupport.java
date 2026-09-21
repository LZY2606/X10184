package com.regmold;

import com.regmold.domain.Diagnostic;
import com.regmold.domain.ParsedModel;
import com.regmold.parse.YamlParser;
import com.regmold.validate.ModelIndex;
import com.regmold.validate.Validator;
import com.regmold.web.ModelService;
import com.regmold.store.ModelRepository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class TestSupport {
    private TestSupport() {
    }

    public static String fixture(String name) {
        try {
            return Files.readString(Path.of("src/test/fixtures", name));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static ParsedModel parse(String yaml) {
        return new YamlParser().parse(yaml);
    }

    public static List<Diagnostic> validate(String yaml) {
        ParsedModel parsed = parse(yaml);
        return new Validator().validate(parsed);
    }

    public static ModelIndex index(String yaml) {
        return new ModelIndex(parse(yaml).model);
    }

    public static boolean hasError(List<Diagnostic> ds, String code) {
        return ds.stream().anyMatch(d -> d.severity == Diagnostic.Severity.ERROR && d.code.equals(code));
    }
}
