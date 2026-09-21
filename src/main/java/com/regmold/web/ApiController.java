package com.regmold.web;

import com.regmold.domain.Field;
import com.regmold.domain.Model;
import com.regmold.domain.Register;
import com.regmold.diff.ModelDiff;
import com.regmold.gen.CodeGenerator;
import com.regmold.io.ModelCanonicalizer;
import com.regmold.sim.Simulator;
import com.regmold.store.ModelRepository;
import com.regmold.validate.ValidationIssue;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@RestController
@RequestMapping("/api")
public class ApiController {

    private final ModelService modelService;
    private final ModelRepository repository;
    private final Map<Long, SimSession> sessions = new ConcurrentHashMap<>();
    private final AtomicLong sessionIds = new AtomicLong();

    public ApiController(ModelService modelService, ModelRepository repository) {
        this.modelService = modelService;
        this.repository = repository;
    }

    @PostMapping("/models")
    public Map<String, Object> importModel(@RequestBody Map<String, String> body) {
        String yaml = body.getOrDefault("yaml", "");
        ModelService.ImportResult result = modelService.importYaml(yaml);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", result.ok());
        out.put("revisionId", result.revisionId());
        out.put("hash", result.hash());
        out.put("reused", result.reused());
        out.put("issues", result.issues().stream().map(this::issueMap).toList());
        return out;
    }

    @GetMapping("/models")
    public List<Map<String, Object>> list() {
        return repository.listRevisions();
    }

    @GetMapping("/models/{id}")
    public Map<String, Object> getModel(@PathVariable long id) {
        return modelView(modelService.requireRevision(id));
    }

    @GetMapping("/models/{id}/canonical")
    public Map<String, Object> canonical(@PathVariable long id) {
        Model m = modelService.requireRevision(id);
        return Map.of("revisionId", id, "canonicalYaml", m.canonicalYaml());
    }

    @PostMapping("/validate")
    public Map<String, Object> validate(@RequestBody Map<String, String> body) {
        String yaml = body.getOrDefault("yaml", "");
        try {
            Model parsed = com.regmold.io.YamlModelParser.parse(yaml);
            Model normalized = ModelCanonicalizer.normalize(parsed);
            String canonical = ModelCanonicalizer.canonicalYaml(normalized);
            List<ValidationIssue> issues = com.regmold.validate.Validator
                    .validate(normalized.withRevision(0, canonical));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", issues.stream().noneMatch(i -> i.severity() == ValidationIssue.Severity.ERROR));
            out.put("hash", ModelCanonicalizer.hash(canonical));
            out.put("canonicalYaml", canonical);
            out.put("issues", issues.stream().map(this::issueMap).toList());
            return out;
        } catch (com.regmold.io.YamlParseException e) {
            return Map.of("ok", false, "issues",
                    e.errors().stream().map(m -> issueMap(ValidationIssue.error("YAML", m))).toList());
        }
    }

    @PostMapping("/models/{id}/generate")
    public Map<String, Object> generate(@PathVariable long id, @RequestBody(required = false) Map<String, String> body)
            throws java.io.IOException {
        Model model = modelService.requireRevision(id);
        String dir = body == null ? null : body.get("targetDir");
        if (dir != null && !dir.isBlank()) {
            List<Path> paths = modelService.generateTo(model, Path.of(dir));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("revisionId", id);
            out.put("installed", paths.stream().map(Path::toString).toList());
            out.put("targetDir", Path.of(dir).toAbsolutePath().normalize().toString());
            return out;
        }
        CodeGenerator.Generated g = modelService.generate(model);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("revisionId", id);
        out.put("hash", g.hash());
        List<Map<String, Object>> files = new ArrayList<>();
        for (var f : g.files()) {
            Map<String, Object> fm = new LinkedHashMap<>();
            fm.put("path", f.relativePath());
            fm.put("content", f.content());
            files.add(fm);
        }
        out.put("files", files);
        return out;
    }

    @GetMapping("/models/{id}/sim/peek/{reg}")
    public Map<String, Object> peek(@PathVariable long id, @PathVariable String reg) {
        Model model = modelService.requireRevision(id);
        long value = new Simulator(model).peek(reg);
        return Map.of("register", reg, "value", "0x" + Long.toUnsignedString(value, 16),
                "consumed", false);
    }

    @PostMapping("/models/{id}/sim")
    public Map<String, Object> createSession(@PathVariable long id) {
        Model model = modelService.requireRevision(id);
        long sid = sessionIds.incrementAndGet();
        sessions.put(sid, new SimSession(sid, id, model));
        return sessions.get(sid).state();
    }

    @PostMapping("/sim/{sid}/reset")
    public Map<String, Object> resetSession(@PathVariable long sid) {
        SimSession s = requireSession(sid);
        s.reset();
        return s.state();
    }

    @GetMapping("/sim/{sid}")
    public Map<String, Object> session(@PathVariable long sid) {
        return requireSession(sid).state();
    }

    @PostMapping("/sim/{sid}/write")
    public Map<String, Object> write(@PathVariable long sid, @RequestBody Map<String, Object> body) {
        SimSession s = requireSession(sid);
        String reg = String.valueOf(body.get("register"));
        long value = parseLong(body.get("value"));
        return s.write(reg, value);
    }

    @PostMapping("/sim/{sid}/read")
    public Map<String, Object> read(@PathVariable long sid, @RequestBody Map<String, Object> body) {
        SimSession s = requireSession(sid);
        String reg = String.valueOf(body.get("register"));
        int word = body.get("word") == null ? 0 : Integer.parseInt(String.valueOf(body.get("word")));
        return s.read(reg, word);
    }

    @PostMapping("/sim/{sid}/atomic")
    public Map<String, Object> atomic(@PathVariable long sid, @RequestBody Map<String, Object> body) {
        SimSession s = requireSession(sid);
        String reg = String.valueOf(body.get("register"));
        long setMask = parseLong(body.getOrDefault("setMask", "0"));
        long clearMask = parseLong(body.getOrDefault("clearMask", "0"));
        return s.atomic(reg, setMask, clearMask);
    }

    @GetMapping("/sim/{sid}/peek/{reg}")
    public Map<String, Object> sessionPeek(@PathVariable long sid, @PathVariable String reg) {
        return requireSession(sid).peek(reg);
    }

    @GetMapping("/diff/{a}/{b}")
    public Map<String, Object> diff(@PathVariable long a, @PathVariable long b) {
        Model ma = modelService.requireRevision(a);
        Model mb = modelService.requireRevision(b);
        ModelDiff.Report report = ModelDiff.compare(ma, mb);
        List<Map<String, Object>> entries = new ArrayList<>();
        for (var e : report.entries()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", e.kind());
            m.put("severity", e.severity());
            m.put("message", e.message());
            m.put("register", e.register());
            m.put("field", e.field());
            m.put("affectedApis", e.affectedApis());
            entries.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", a);
        out.put("to", b);
        out.put("entries", entries);
        out.put("addedApis", report.addedApis());
        out.put("removedApis", report.removedApis());
        out.put("impactedApis", report.impactedApis());
        return out;
    }

    private SimSession requireSession(long sid) {
        SimSession s = sessions.get(sid);
        if (s == null) {
            throw new IllegalArgumentException("simulation session not found: " + sid);
        }
        return s;
    }

    private Map<String, Object> modelView(Model m) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("revisionId", m.revisionId());
        out.put("name", m.name());
        out.put("version", m.version());
        out.put("hash", ModelCanonicalizer.hash(m.canonicalYaml()));
        out.put("addressBits", m.addressBits());
        out.put("wordBytes", m.wordBytes());
        out.put("endianness", m.endianness().name().toLowerCase());
        List<Map<String, Object>> regs = new ArrayList<>();
        for (Register r : m.registers()) {
            Map<String, Object> rm = new LinkedHashMap<>();
            rm.put("name", r.name());
            rm.put("address", "0x" + Long.toUnsignedString(r.address(), 16));
            rm.put("widthBits", r.widthBits());
            rm.put("access", r.access().key());
            rm.put("aliasOf", r.aliasOf());
            rm.put("readOrder", r.readOrder() == null ? null : r.readOrder().key());
            List<Map<String, Object>> fields = new ArrayList<>();
            for (Field f : r.fields()) {
                Map<String, Object> fm = new LinkedHashMap<>();
                fm.put("name", f.name());
                fm.put("lsb", f.lsb());
                fm.put("msb", f.msb());
                fm.put("width", f.width());
                fm.put("access", f.access().key());
                fm.put("reset", "0x" + Long.toUnsignedString(f.reset(), 16));
                fm.put("description", f.description());
                fields.add(fm);
            }
            rm.put("fields", fields);
            regs.add(rm);
        }
        out.put("registers", regs);
        List<Map<String, Object>> effects = new ArrayList<>();
        for (var e : m.effects()) {
            Map<String, Object> em = new LinkedHashMap<>();
            em.put("trigger", e.trigger().canonical());
            em.put("action", e.action().name().toLowerCase());
            em.put("target", e.target().canonical());
            effects.add(em);
        }
        out.put("effects", effects);
        List<Map<String, Object>> locks = new ArrayList<>();
        for (var l : m.locks()) {
            Map<String, Object> lm = new LinkedHashMap<>();
            lm.put("target", l.target().canonical());
            lm.put("master", l.master().canonical());
            locks.add(lm);
        }
        out.put("locks", locks);
        out.put("issues", modelService.revalidate(m).stream().map(this::issueMap).toList());
        return out;
    }

    private Map<String, Object> issueMap(ValidationIssue i) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("severity", i.severity().name().toLowerCase());
        m.put("code", i.code());
        m.put("message", i.message());
        m.put("path", i.path());
        return m;
    }

    private static long parseLong(Object v) {
        if (v == null) {
            return 0L;
        }
        String s = String.valueOf(v).trim().replace("_", "");
        if (s.startsWith("0x") || s.startsWith("0X")) {
            return Long.parseUnsignedLong(s.substring(2), 16);
        }
        return Long.parseLong(s);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("ok", false, "error", e.getMessage()));
    }
}
