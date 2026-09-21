package com.regmold.web;

import com.regmold.codegen.ArtifactWriter;
import com.regmold.codegen.Codegen;
import com.regmold.impact.ApiChange;
import com.regmold.impact.ImpactAnalyzer;
import com.regmold.model.ChipModel;
import com.regmold.sim.SimOp;
import com.regmold.sim.SimStep;
import com.regmold.sim.Simulator;
import com.regmold.store.ProjectStore;
import com.regmold.validate.Conflict;
import com.regmold.validate.Validator;
import com.regmold.yaml.ModelParser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

@RestController
@RequestMapping("/api")
public class ApiController {

    private final ProjectStore store;
    private final Validator validator = new Validator();
    private final Path genDir;

    public ApiController(ProjectStore store, @Value("${regmold.gen.dir:data/gen}") String genDir) {
        this.store = store;
        this.genDir = Path.of(genDir);
    }

    public record ImportRequest(String name, String yaml, String message) {}
    public record ImportResponse(long projectId, int version, List<Conflict> conflicts) {}

    @PostMapping("/projects")
    public ImportResponse createProject(@RequestBody ImportRequest req) {
        ChipModel model = ModelParser.parse(req.yaml());
        long id = store.createProject(req.name() == null || req.name().isBlank() ? model.name() : req.name());
        int version = store.addRevision(id, req.yaml(), ModelParser.fingerprint(model), req.message());
        return new ImportResponse(id, version, validator.validate(model));
    }

    @PostMapping("/projects/{id}/revisions")
    public Map<String, Object> addRevision(@PathVariable long id, @RequestBody ImportRequest req) {
        ChipModel model = ModelParser.parse(req.yaml());
        int version = store.addRevision(id, req.yaml(), ModelParser.fingerprint(model), req.message());
        return Map.of("version", version, "conflicts", validator.validate(model));
    }

    private ChipModel modelAt(long projectId, int version) {
        String yaml = (String) store.revision(projectId, version).get("yaml");
        return ModelParser.parse(yaml);
    }

    @GetMapping("/projects/{id}/revisions/{v}/model")
    public ChipModel model(@PathVariable long id, @PathVariable int v) {
        return modelAt(id, v);
    }

    @GetMapping("/projects/{id}/revisions/{v}/conflicts")
    public List<Conflict> conflicts(@PathVariable long id, @PathVariable int v) {
        return validator.validate(modelAt(id, v));
    }

    public record SimRequest(String script) {}

    @PostMapping("/projects/{id}/revisions/{v}/simulate")
    public List<SimStep> simulate(@PathVariable long id, @PathVariable int v, @RequestBody SimRequest req) {
        ChipModel model = modelAt(id, v);
        List<SimOp> ops = new ArrayList<>();
        for (String line : req.script().split("\\R")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] parts = line.split("\\s+");
            switch (parts[0].toLowerCase()) {
                case "read" -> ops.add(SimOp.read(parts[1]));
                case "preview" -> ops.add(SimOp.preview(parts[1]));
                case "write" -> ops.add(SimOp.write(parts[1], Long.decode(parts[2])));
                default -> throw new IllegalArgumentException("unknown op in line: " + line);
            }
        }
        return new Simulator(model).run(ops);
    }

    @PostMapping("/projects/{id}/revisions/{v}/generate")
    public Map<String, Object> generate(@PathVariable long id, @PathVariable int v) throws IOException {
        ChipModel model = modelAt(id, v);
        Codegen gen = new Codegen(model, String.valueOf(v));
        Map<String, String> files = gen.generateAll();
        Path dir = genDir.resolve(String.valueOf(id)).resolve(String.valueOf(v));
        ArtifactWriter.writeAtomic(dir, files);
        return Map.of("dir", dir.toString(), "fingerprint", gen.fingerprint(), "files", files.keySet());
    }

    @GetMapping("/projects/{id}/revisions/{v}/artifact")
    public ResponseEntity<String> artifact(@PathVariable long id, @PathVariable int v,
                                           @RequestParam String path) throws IOException {
        if (path.contains("..")) return ResponseEntity.badRequest().build();
        Path file = genDir.resolve(String.valueOf(id)).resolve(String.valueOf(v)).resolve(path);
        if (!Files.isRegularFile(file)) return ResponseEntity.notFound().build();
        return ResponseEntity.ok()
                .contentType(path.endsWith(".md") ? MediaType.TEXT_MARKDOWN : MediaType.TEXT_PLAIN)
                .body(Files.readString(file));
    }

    @GetMapping("/projects/{id}/diff")
    public List<ApiChange> diff(@PathVariable long id, @RequestParam int a, @RequestParam int b) {
        return new ImpactAnalyzer().diff(modelAt(id, a), modelAt(id, b));
    }
}
