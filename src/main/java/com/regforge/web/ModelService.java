package com.regforge.web;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.regforge.model.ChipModel;
import com.regforge.sim.SimOp;
import com.regforge.sim.Simulator;
import com.regforge.sim.StepResult;
import com.regforge.store.ModelStore;
import com.regforge.store.VersionRow;
import com.regforge.validate.Diagnostic;
import com.regforge.validate.Validator;
import com.regforge.yaml.ModelParseException;
import com.regforge.yaml.YamlModelParser;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ModelService {

    private final ModelStore store;
    private final YamlModelParser parser = new YamlModelParser();
    private final Validator validator = new Validator();
    private final ObjectMapper json;

    public ModelService(ModelStore store, ObjectMapper json) {
        this.store = store;
        this.json = json;
    }

    public record ImportResult(VersionRow row, List<Diagnostic> diagnostics, boolean valid,
                               String parseError) {
    }

    public ImportResult importRevision(String name, String yaml) {
        String requestedName = (name == null || name.isBlank()) ? "model" : name.trim();
        ChipModel model = null;
        String hash = "invalid";
        String canonical = "";
        List<Diagnostic> diagnostics = new ArrayList<>();
        String parseError = null;
        try {
            model = parser.parse(yaml);
            canonical = parser.canonicalize(model);
            hash = parser.hashOf(model);
            diagnostics = validator.validate(model);
            requestedName = model.name();
        } catch (ModelParseException e) {
            parseError = e.getMessage();
            diagnostics = List.of(Diagnostic.error("YAML_PARSE_ERROR", parseError, List.of()));
        }
        String diagJson;
        try {
            diagJson = json.writeValueAsString(diagnostics);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        VersionRow row = store.insert(requestedName, hash, yaml, canonical, diagJson);
        return new ImportResult(row, diagnostics, parseError == null
                && diagnostics.stream().noneMatch(d -> d.severity() == Diagnostic.Severity.ERROR),
                parseError);
    }

    public ChipModel loadModel(VersionRow row) {
        return parser.parse(row.canonical().isEmpty() ? row.yaml() : row.canonical());
    }

    public List<Diagnostic> diagnostics(VersionRow row) {
        try {
            return json.readValue(row.diagnostics(), new TypeReference<>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public Map<String, Object> summary(VersionRow row) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", row.id());
        map.put("name", row.name());
        map.put("hash", row.hash());
        map.put("createdAt", row.createdAt().toString());
        List<Diagnostic> diags = diagnostics(row);
        map.put("valid", diags.stream().noneMatch(d -> d.severity() == Diagnostic.Severity.ERROR));
        map.put("errorCount", diags.stream()
                .filter(d -> d.severity() == Diagnostic.Severity.ERROR).count());
        map.put("warningCount", diags.stream()
                .filter(d -> d.severity() == Diagnostic.Severity.WARNING).count());
        map.put("infoCount", diags.stream()
                .filter(d -> d.severity() == Diagnostic.Severity.INFO).count());
        return map;
    }

    public List<StepResult> simulate(ChipModel model, List<SimOp> ops) {
        Simulator simulator = new Simulator(model);
        List<StepResult> results = new ArrayList<>();
        for (int i = 0; i < ops.size(); i++) {
            results.add(simulator.apply(i, ops.get(i)));
        }
        return results;
    }

    public ObjectMapper json() {
        return json;
    }

    public YamlModelParser parser() {
        return parser;
    }

    public Validator validator() {
        return validator;
    }

    public ModelStore store() {
        return store;
    }
}
