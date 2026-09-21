package com.regforge.web;

import com.regforge.gen.CGenerator;
import com.regforge.gen.DocGenerator;
import com.regforge.gen.GenBundle;
import com.regforge.gen.GeneratedFile;
import com.regforge.gen.ModelDiffer;
import com.regforge.gen.RustGenerator;
import com.regforge.model.Canonical;
import com.regforge.model.RegisterModel;
import com.regforge.parse.ParseResult;
import com.regforge.parse.YamlParser;
import com.regforge.sim.Simulator;
import com.regforge.store.ModelRecord;
import com.regforge.store.ModelRepository;
import com.regforge.validate.Issue;
import com.regforge.validate.Validator;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ModelService {

    private final YamlParser parser;
    private final Validator validator;
    private final ModelRepository repo;
    private final CGenerator cGen;
    private final RustGenerator rustGen;
    private final DocGenerator docGen;
    private final ModelDiffer differ;

    public ModelService(YamlParser parser, Validator validator, ModelRepository repo,
                        CGenerator cGen, RustGenerator rustGen, DocGenerator docGen, ModelDiffer differ) {
        this.parser = parser;
        this.validator = validator;
        this.repo = repo;
        this.cGen = cGen;
        this.rustGen = rustGen;
        this.docGen = docGen;
        this.differ = differ;
    }

    public ParseOutcome parseAndValidate(String yaml) {
        ParseResult pr = parser.parse(yaml);
        List<Issue> issues = new ArrayList<>(pr.issues());
        issues.addAll(validator.validate(pr.model()));
        return new ParseOutcome(pr.model(), issues);
    }

    /**
     * Import a YAML revision.  The original YAML and canonical form are both
     * retained.  Identical semantics (regardless of key order) reuse the same
     * content hash and are not stored as a new revision.
     */
    public synchronized ImportResult importYaml(String yaml, boolean force) {
        ParseOutcome outcome = parseAndValidate(yaml);
        boolean errors = outcome.issues().stream().anyMatch(i -> i.severity() == Issue.Severity.ERROR);
        String hash = Canonical.hash(outcome.model());
        if (errors && !force) {
            return new ImportResult(null, hash, outcome.issues(), false, "校验存在错误，未保存");
        }
        var existing = repo.findByHash(hash);
        if (existing.isPresent()) {
            ModelRecord rec = existing.get();
            return new ImportResult(rec, hash, outcome.issues(), false,
                    "语义与修订 #" + rec.id() + " 完全相同（内容哈希一致），不新增修订");
        }
        ModelRecord rec = repo.insert(outcome.model().getName(), outcome.model().getVersion(),
                hash, Canonical.text(outcome.model()), yaml);
        return new ImportResult(rec, hash, outcome.issues(), true, "已保存为修订 #" + rec.id());
    }

    public List<GeneratedFile> generate(long revision) {
        ModelRecord rec = repo.findById(revision).orElseThrow(() -> new IllegalArgumentException("修订不存在: " + revision));
        RegisterModel model = parser.parse(rec.yaml()).model();
        List<GeneratedFile> all = new ArrayList<>();
        for (GenBundle b : List.of(cGen.generate(model, revision),
                rustGen.generate(model, revision),
                docGen.generate(model, revision))) {
            all.addAll(b.files());
        }
        all.sort((a, b2) -> a.relativePath().compareTo(b2.relativePath()));
        return all;
    }

    public Map<String, Object> compare(long revA, long revB) {
        ModelRecord a = repo.findById(revA).orElseThrow();
        ModelRecord b = repo.findById(revB).orElseThrow();
        RegisterModel ma = parser.parse(a.yaml()).model();
        RegisterModel mb = parser.parse(b.yaml()).model();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", summary(a));
        out.put("to", summary(b));
        out.put("diff", differ.diff(ma, mb));
        return out;
    }

    public Map<String, Object> summary(ModelRecord r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id());
        m.put("name", r.name());
        m.put("version", r.version());
        m.put("hash", r.contentHash());
        m.put("createdAt", r.createdAt());
        return m;
    }

    public Simulator simulator(long revision) {
        ModelRecord r = repo.findById(revision).orElseThrow();
        return new Simulator(parser.parse(r.yaml()).model());
    }

    public RegisterModel modelOf(long revision) {
        ModelRecord r = repo.findById(revision).orElseThrow();
        return parser.parse(r.yaml()).model();
    }

    public record ParseOutcome(RegisterModel model, List<Issue> issues) {}

    public record ImportResult(ModelRecord record, String hash, List<Issue> issues,
                               boolean created, String message) {}
}
