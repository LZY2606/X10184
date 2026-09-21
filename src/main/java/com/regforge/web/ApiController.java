package com.regforge.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.regforge.codegen.CodegenService;
import com.regforge.diff.DiffService;
import com.regforge.diff.ModelDiff;
import com.regforge.model.ChipModel;
import com.regforge.sim.SimOp;
import com.regforge.sim.StepResult;
import com.regforge.store.VersionRow;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ApiController {

    private final ModelService modelService;
    private final CodegenService codegen;
    private final DiffService diffService = new DiffService();

    public ApiController(ModelService modelService, CodegenService codegen) {
        this.modelService = modelService;
        this.codegen = codegen;
    }

    @GetMapping("/versions")
    public List<Map<String, Object>> versions() {
        return modelService.store().list().stream().map(modelService::summary).toList();
    }

    @PostMapping("/versions")
    public Map<String, Object> importVersion(@RequestBody JsonNode body) {
        String yaml = body.path("yaml").asText(null);
        if (yaml == null || yaml.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "yaml 不能为空");
        }
        String name = body.path("name").asText(null);
        ModelService.ImportResult result = modelService.importRevision(name, yaml);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", result.row().id());
        response.put("name", result.row().name());
        response.put("hash", result.row().hash());
        response.put("valid", result.valid());
        response.put("diagnostics", result.diagnostics());
        return response;
    }

    @GetMapping("/versions/{id}")
    public Map<String, Object> version(@PathVariable long id) {
        VersionRow row = requireRow(id);
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", row.id());
        map.put("name", row.name());
        map.put("hash", row.hash());
        map.put("createdAt", row.createdAt().toString());
        map.put("yaml", row.yaml());
        map.put("canonical", row.canonical());
        map.put("diagnostics", modelService.diagnostics(row));
        return map;
    }

    @GetMapping("/versions/{id}/model")
    public ChipModel model(@PathVariable long id) {
        VersionRow row = requireValidRow(id);
        return modelService.loadModel(row);
    }

    @GetMapping("/versions/{id}/conflicts")
    public Map<String, Object> conflicts(@PathVariable long id) {
        VersionRow row = requireRow(id);
        var diagnostics = modelService.diagnostics(row);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", row.id());
        result.put("hash", row.hash());
        result.put("errors", diagnostics.stream()
                .filter(d -> d.severity() == com.regforge.validate.Diagnostic.Severity.ERROR).toList());
        result.put("warnings", diagnostics.stream()
                .filter(d -> d.severity() == com.regforge.validate.Diagnostic.Severity.WARNING).toList());
        result.put("infos", diagnostics.stream()
                .filter(d -> d.severity() == com.regforge.validate.Diagnostic.Severity.INFO).toList());
        return result;
    }

    @PostMapping("/versions/{id}/simulate")
    public Map<String, Object> simulate(@PathVariable long id, @RequestBody JsonNode body) {
        VersionRow row = requireValidRow(id);
        ChipModel model = modelService.loadModel(row);
        List<SimOp> ops = new ArrayList<>();
        for (JsonNode step : body.path("steps")) {
            String typeRaw = step.path("type").asText("READ").toUpperCase();
            String register = step.path("register").asText(null);
            if (register == null || register.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "每一步都需要 register");
            }
            SimOp.Type type = switch (typeRaw) {
                case "READ" -> SimOp.Type.READ;
                case "PEEK" -> SimOp.Type.PEEK;
                case "WRITE" -> SimOp.Type.WRITE;
                default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "未知步骤类型: " + typeRaw);
            };
            Long value = null;
            if (type == SimOp.Type.WRITE) {
                JsonNode v = step.get("value");
                if (v == null || v.isNull()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "WRITE 步骤需要 value");
                }
                String raw = v.asText();
                try {
                    value = raw.startsWith("0x") ? Long.parseUnsignedLong(raw.substring(2), 16)
                            : Long.parseUnsignedLong(raw);
                } catch (NumberFormatException e) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "非法数值: " + raw);
                }
            }
            ops.add(new SimOp(type, register, value, null));
        }
        List<StepResult> results = modelService.simulate(model, ops);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("hash", row.hash());
        result.put("steps", results);
        return result;
    }

    @GetMapping(value = "/versions/{id}/codegen", produces = MediaType.TEXT_PLAIN_VALUE)
    public String codegen(@PathVariable long id, @RequestParam String lang) {
        VersionRow row = requireValidRow(id);
        ChipModel model = modelService.loadModel(row);
        Map<String, String> files = codegen.render(model, row.hash(), lang);
        return String.join("\n", files.values());
    }

    @PostMapping("/versions/{id}/generate")
    public Map<String, Object> generate(@PathVariable long id, @RequestBody JsonNode body) {
        VersionRow row = requireValidRow(id);
        ChipModel model = modelService.loadModel(row);
        String dir = body.path("dir").asText("generated");
        try {
            Path outDir = Path.of(dir).toAbsolutePath();
            codegen.generateToDir(model, row.hash(), outDir);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("dir", outDir.toString());
            result.put("files", codegen.renderAll(model, row.hash()).keySet());
            return result;
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "生成失败（未留下半套文件）: " + e.getMessage());
        }
    }

    @GetMapping("/diff")
    public ModelDiff diff(@RequestParam long from, @RequestParam long to) {
        VersionRow a = requireValidRow(from);
        VersionRow b = requireValidRow(to);
        ChipModel ma = modelService.loadModel(a);
        ChipModel mb = modelService.loadModel(b);
        return diffService.diff(ma, mb, a.hash(), b.hash());
    }

    private VersionRow requireRow(long id) {
        VersionRow row = modelService.store().get(id);
        if (row == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "版本不存在: " + id);
        }
        return row;
    }

    private VersionRow requireValidRow(long id) {
        VersionRow row = requireRow(id);
        if ("invalid".equals(row.hash())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "版本 " + id + " 的原始定义无法解析，不能执行该操作");
        }
        if (modelService.diagnostics(row).stream()
                .anyMatch(d -> d.severity() == com.regforge.validate.Diagnostic.Severity.ERROR)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "版本 " + id + " 存在校验冲突，请先在冲突页处理");
        }
        return row;
    }
}
