package com.regmold.web;

import com.regmold.domain.Model;
import com.regmold.gen.CodeGenerator;
import com.regmold.io.ModelCanonicalizer;
import com.regmold.io.YamlModelParser;
import com.regmold.io.YamlParseException;
import com.regmold.store.ModelRepository;
import com.regmold.validate.ValidationException;
import com.regmold.validate.ValidationIssue;
import com.regmold.validate.Validator;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;

@Service
public class ModelService {

    private final ModelRepository repository;

    public ModelService(ModelRepository repository) {
        this.repository = repository;
    }

    public record ImportResult(long revisionId, String hash, boolean reused,
                               List<ValidationIssue> issues) {
        public boolean ok() {
            return issues.stream().noneMatch(i -> i.severity() == ValidationIssue.Severity.ERROR);
        }
    }

    public ImportResult importYaml(String yaml) {
        Model parsed;
        try {
            parsed = YamlModelParser.parse(yaml);
        } catch (YamlParseException e) {
            return new ImportResult(0, "", false,
                    e.errors().stream().map(m -> ValidationIssue.error("YAML", m)).toList());
        }
        Model normalized = ModelCanonicalizer.normalize(parsed);
        String canonical = ModelCanonicalizer.canonicalYaml(normalized);
        String hash = ModelCanonicalizer.hash(canonical);
        Model candidate = normalized.withRevision(0, canonical);

        List<ValidationIssue> issues = Validator.validate(candidate);
        boolean hasErrors = issues.stream().anyMatch(i -> i.severity() == ValidationIssue.Severity.ERROR);
        if (hasErrors) {
            return new ImportResult(0, hash, false, issues);
        }
        long existing = repository.findIdByHash(hash);
        if (existing > 0) {
            return new ImportResult(existing, hash, true, issues);
        }
        long id = repository.insert(candidate, canonical);
        return new ImportResult(id, hash, false, issues);
    }

    public Model requireRevision(long id) {
        Model m = repository.loadRevision(id);
        if (m == null) {
            throw new IllegalArgumentException("revision not found: " + id);
        }
        return m;
    }

    public List<ValidationIssue> revalidate(Model model) {
        return Validator.validate(model);
    }

    public CodeGenerator.Generated generate(Model model) {
        return CodeGenerator.build(model);
    }

    public List<java.nio.file.Path> generateTo(Model model, Path target) throws java.io.IOException {
        CodeGenerator.Generated g = CodeGenerator.build(model);
        List<Path> paths = CodeGenerator.installAtomic(g, target);
        repository.recordGeneration(model.revisionId(), target.toAbsolutePath().toString());
        return paths;
    }
}
