package com.regmold.web;

import com.regmold.domain.Diagnostic;
import com.regmold.domain.ParsedModel;
import com.regmold.diff.DiffEntry;
import com.regmold.diff.ModelDiffer;
import com.regmold.gen.CodeGenerator;
import com.regmold.gen.GenResult;
import com.regmold.parse.YamlParser;
import com.regmold.sim.SimOperation;
import com.regmold.sim.SimStep;
import com.regmold.sim.Simulator;
import com.regmold.store.ModelRepository;
import com.regmold.validate.ModelIndex;
import com.regmold.validate.Validator;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ModelService {

    private final YamlParser parser = new YamlParser();
    private final Validator validator = new Validator();
    private final CodeGenerator generator = new CodeGenerator();
    private final ModelDiffer differ = new ModelDiffer();
    private final ModelRepository repository;

    public ModelService(ModelRepository repository) {
        this.repository = repository;
    }

    public ParsedBundle analyze(String yaml) {
        ParsedModel parsed = parser.parse(yaml);
        List<Diagnostic> diagnostics = validator.validate(parsed);
        return new ParsedBundle(parsed, diagnostics);
    }

    public Map<String, Object> importYaml(String yaml, boolean persist) {
        ParsedBundle bundle = analyze(yaml);
        long revision = -1;
        if (persist) {
            revision = repository.addRevision(bundle.parsed.model.name, bundle.parsed.semanticHash,
                    bundle.parsed.canonicalYaml, yaml, bundle.parsed.valid());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", bundle.parsed.model.name);
        out.put("semanticHash", bundle.parsed.semanticHash);
        out.put("canonicalYaml", bundle.parsed.canonicalYaml);
        out.put("valid", bundle.parsed.valid());
        out.put("revision", revision);
        out.put("diagnostics", bundle.diagnostics);
        out.put("registers", registerViews(bundle));
        return out;
    }

    public List<Map<String, Object>> registerViews(ParsedBundle bundle) {
        ModelIndex index = new ModelIndex(bundle.parsed.model);
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (var r : bundle.parsed.model.registers) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("name", r.name);
            view.put("offset", r.offset);
            view.put("offsetHex", "0x" + Long.toUnsignedString(r.offset, 16));
            view.put("width", index.width(r));
            view.put("aliasOf", r.aliasOf);
            view.put("aliasWrite", r.aliasWrite);
            view.put("noClearRead", r.noClearRead);
            view.put("lock", r.lock == null ? null : Map.of("register", r.lock.register, "bit", r.lock.bit));
            List<Map<String, Object>> fields = new java.util.ArrayList<>();
            for (var f : r.fields) {
                Map<String, Object> fv = new LinkedHashMap<>();
                fv.put("name", f.name);
                fv.put("bitStart", f.bitStart);
                fv.put("bitEnd", f.bitEnd());
                fv.put("bitCount", f.bitCount);
                fv.put("access", f.access.name());
                fv.put("reset", f.reset);
                fv.put("resetHex", "0x" + Long.toUnsignedString(f.reset, 16));
                fv.put("maskHex", "0x" + Long.toUnsignedString(f.linearMask(), 16));
                fv.put("latchFrom", f.latchFrom);
                fv.put("description", f.description);
                fields.add(fv);
            }
            view.put("fields", fields);
            out.add(view);
        }
        return out;
    }

    public Map<String, Object> simulate(String yaml, List<SimOperation> operations) {
        ParsedBundle bundle = analyze(yaml);
        Simulator simulator = new Simulator(new ModelIndex(bundle.parsed.model));
        List<SimStep> steps = simulator.run(operations);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("steps", steps);
        out.put("finalState", simulator.snapshot());
        out.put("diagnostics", bundle.diagnostics);
        return out;
    }

    public List<DiffEntry> diff(String yamlA, String yamlB) {
        ModelIndex a = new ModelIndex(analyze(yamlA).parsed.model);
        ModelIndex b = new ModelIndex(analyze(yamlB).parsed.model);
        return differ.diff(a.model, b.model);
    }

    public GenResult generate(String yaml) {
        ParsedBundle bundle = analyze(yaml);
        if (!bundle.parsed.valid()) {
            throw new BadRequestException("model has validation errors; fix them before generating code");
        }
        return generator.generate(bundle.parsed.model, bundle.parsed.semanticHash, bundle.diagnostics);
    }

    public ModelRepository repository() {
        return repository;
    }

    public record ParsedBundle(ParsedModel parsed, List<Diagnostic> diagnostics) {
    }
}
