package com.regforge.web;

import com.regforge.gen.GeneratedFile;
import com.regforge.model.Field;
import com.regforge.model.Register;
import com.regforge.model.RegisterModel;
import com.regforge.sim.StepResult;
import com.regforge.sim.Simulator;
import com.regforge.store.ModelRecord;
import com.regforge.store.ModelRepository;
import com.regforge.util.Numbers;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api")
public class ApiController {

    private final ModelService service;
    private final ModelRepository repo;
    private final Map<String, SimSession> sessions = new ConcurrentHashMap<>();

    public ApiController(ModelService service, ModelRepository repo) {
        this.service = service;
        this.repo = repo;
    }

    @GetMapping("/models")
    public List<Map<String, Object>> models() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ModelRecord r : repo.findAll()) out.add(service.summary(r));
        return out;
    }

    @GetMapping("/models/{id}")
    public Map<String, Object> model(@PathVariable long id) {
        ModelRecord r = repo.findById(id).orElseThrow();
        RegisterModel m = service.modelOf(id);
        Map<String, Object> out = new LinkedHashMap<>(service.summary(r));
        out.put("yaml", r.yaml());
        out.put("layout", layout(m));
        return out;
    }

    @PostMapping("/validate")
    public Map<String, Object> validate(@RequestBody Map<String, String> body) {
        String yaml = body.getOrDefault("yaml", "");
        ModelService.ParseOutcome oc = service.parseAndValidate(yaml);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("issues", oc.issues());
        out.put("hash", com.regforge.model.Canonical.hash(oc.model()));
        out.put("layout", layout(oc.model()));
        return out;
    }

    @PostMapping("/import")
    public Map<String, Object> importYaml(@RequestBody Map<String, Object> body) {
        String yaml = String.valueOf(body.getOrDefault("yaml", ""));
        boolean force = Boolean.TRUE.equals(body.get("force"));
        ModelService.ImportResult res = service.importYaml(yaml, force);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("created", res.created());
        out.put("message", res.message());
        out.put("hash", res.hash());
        out.put("issues", res.issues());
        if (res.record() != null) out.put("revision", res.record().id());
        return out;
    }

    @GetMapping("/diff/{a}/{b}")
    public Map<String, Object> diff(@PathVariable long a, @PathVariable long b) {
        return service.compare(a, b);
    }

    @GetMapping("/generate/{revision}")
    public List<Map<String, String>> listGenerated(@PathVariable long revision) {
        List<Map<String, String>> out = new ArrayList<>();
        for (GeneratedFile f : service.generate(revision)) {
            out.add(Map.of("path", f.relativePath(), "content", f.content()));
        }
        return out;
    }

    // ---- simulation ----

    @PostMapping("/sim/{revision}/start")
    public Map<String, Object> start(@PathVariable long revision) {
        SimSession session = new SimSession(revision, service.simulator(revision));
        sessions.put(session.id, session);
        return Map.of("session", session.id, "state", session.simulator.snapshot());
    }

    @PostMapping("/sim/{session}/step")
    public Map<String, Object> step(@PathVariable String session, @RequestBody Map<String, Object> body) {
        SimSession s = sessions.get(session);
        if (s == null) throw new IllegalArgumentException("会话不存在: " + session);
        String type = String.valueOf(body.getOrDefault("type", "write"));
        String target = String.valueOf(body.getOrDefault("target", ""));
        long value = Numbers.parseLong(body.get("value"), 0L);
        StepResult result = s.simulator.step(type, target, value);
        s.trace.add(result);
        return Map.of(
                "step", result.step(),
                "type", result.type(),
                "target", result.target(),
                "readWords", hexWords(result.readWords()),
                "events", result.events(),
                "state", result.registers());
    }

    @PostMapping("/sim/{session}/reset")
    public Map<String, Object> reset(@PathVariable String session) {
        SimSession s = sessions.get(session);
        if (s == null) return Map.of("error", "会话不存在");
        s.simulator.reset();
        s.trace.clear();
        return Map.of("state", s.simulator.snapshot());
    }

    private List<String> hexWords(List<Long> words) {
        List<String> out = new ArrayList<>();
        for (Long w : words) out.add(Numbers.hex64(w));
        return out;
    }

    /** Serializable bit layout used by the clickable UI. */
    private Map<String, Object> layout(RegisterModel m) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", m.getName());
        out.put("wordBits", m.getWordBits());
        out.put("endian", m.getEndian());
        List<Map<String, Object>> regs = new ArrayList<>();
        for (Register r : m.getRegisters()) {
            Map<String, Object> rm = new LinkedHashMap<>();
            rm.put("name", r.getName());
            rm.put("address", Numbers.hex64(r.getAddress()));
            rm.put("width", r.getWidthBits());
            rm.put("reset", Numbers.hex64(r.getReset()));
            rm.put("aliasOf", r.getAliasOf());
            rm.put("readOrder", r.getReadOrder());
            rm.put("lockedBy", r.getLockedBy());
            rm.put("setAlias", r.getSetAlias());
            rm.put("clrAlias", r.getClrAlias());
            rm.put("description", r.getDescription());
            List<Map<String, Object>> fields = new ArrayList<>();
            for (Field f : r.getFields()) {
                Map<String, Object> fm = new LinkedHashMap<>();
                fm.put("name", f.getName());
                fm.put("lsb", f.getLsb());
                fm.put("msb", f.getMsb());
                fm.put("access", f.getAccess().name());
                fm.put("reset", Numbers.hex64(f.getReset()));
                fm.put("mask", Numbers.hex64(f.mask()));
                fm.put("description", f.getDescription());
                List<String> eff = f.getEffects().stream()
                        .map(e -> e.normalizedAction() + " " + e.target()).toList();
                fm.put("effects", eff);
                fields.add(fm);
            }
            rm.put("fields", fields);
            regs.add(rm);
        }
        out.put("registers", regs);
        return out;
    }
}
