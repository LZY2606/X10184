package com.regmold.web;

import com.regmold.gen.AtomicFileWriter;
import com.regmold.gen.GenResult;
import com.regmold.sim.SimOperation;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@RestController
@RequestMapping("/api")
public class ApiController {

    private final ModelService service;

    public ApiController(ModelService service) {
        this.service = service;
    }

    @PostMapping("/analyze")
    public Map<String, Object> analyze(@RequestBody Map<String, String> body) {
        return service.importYaml(body.getOrDefault("yaml", ""), false);
    }

    @PostMapping("/revisions")
    public Map<String, Object> createRevision(@RequestBody Map<String, String> body) {
        return service.importYaml(body.getOrDefault("yaml", ""), true);
    }

    @GetMapping("/models")
    public List<Map<String, Object>> models() {
        return service.repository().listModels();
    }

    @GetMapping("/models/{name}/revisions")
    public List<Map<String, Object>> revisions(@PathVariable String name) {
        return service.repository().listRevisions(name);
    }

    @GetMapping("/models/{name}/revisions/{revision}")
    public Map<String, Object> revision(@PathVariable String name, @PathVariable int revision) {
        Map<String, Object> row = service.repository().getRevision(name, revision);
        if (row == null) {
            throw new BadRequestException("revision not found");
        }
        return row;
    }

    @PostMapping("/simulate")
    public Map<String, Object> simulate(@RequestBody SimRequest request) {
        return service.simulate(request.yaml == null ? "" : request.yaml,
                request.operations == null ? List.of() : request.operations);
    }

    @PostMapping("/diff")
    public Map<String, Object> diff(@RequestBody DiffRequest request) {
        return Map.of("entries", service.diff(request.yamlA, request.yamlB));
    }

    @PostMapping("/generate")
    public Map<String, Object> generate(@RequestBody Map<String, String> body) {
        GenResult result = service.generate(body.getOrDefault("yaml", ""));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("semanticHash", result.semanticHash);
        out.put("files", result.files);
        return out;
    }

    @PostMapping("/generate.zip")
    public ResponseEntity<byte[]> generateZip(@RequestBody Map<String, String> body) throws IOException {
        GenResult result = service.generate(body.getOrDefault("yaml", ""));
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bos, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, String> entry : result.files.entrySet()) {
                zip.putNextEntry(new ZipEntry("generated/" + entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"register-mold-generated.zip\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(bos.toByteArray());
    }

    @PostMapping("/generate-to")
    public Map<String, Object> generateTo(@RequestBody Map<String, String> body) throws IOException {
        String dir = body.get("dir");
        if (dir == null || dir.isBlank()) {
            throw new BadRequestException("dir is required");
        }
        GenResult result = service.generate(body.getOrDefault("yaml", ""));
        new AtomicFileWriter().writeAll(Path.of(dir), result);
        return Map.of("semanticHash", result.semanticHash, "directory", dir, "files", result.files.keySet());
    }

    public static class SimRequest {
        public String yaml;
        public List<SimOperation> operations;
    }

    public static class DiffRequest {
        public String yamlA;
        public String yamlB;
    }
}
