package com.regmold.codegen;

import com.regmold.model.*;
import com.regmold.yaml.ModelParser;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Deterministic code generation. Output depends only on the canonical model
 * and the version label: same semantics -> identical bytes, regardless of YAML key order.
 */
public class Codegen {

    private final ChipModel model;
    private final String version;
    private final String fingerprint;

    public Codegen(ChipModel model, String version) {
        this.model = model;
        this.version = version;
        this.fingerprint = ModelParser.fingerprint(model);
    }

    public String fingerprint() { return fingerprint; }

    /** Generate all artifacts in memory. Nothing touches disk here, so a failure leaves no partial output. */
    public Map<String, String> generateAll() {
        Map<String, String> out = new LinkedHashMap<>();
        String chip = sanitize(model.name());
        out.put("c/regmold_" + chip + ".h", new CGenerator(model, version, fingerprint).header());
        out.put("c/regmold_" + chip + ".c", new CGenerator(model, version, fingerprint).source());
        out.put("rust/regmold_" + chip + ".rs", new RustGenerator(model, version, fingerprint).source());
        out.put("docs/" + chip + ".md", new DocGenerator(model, version, fingerprint).markdown());
        out.put("MANIFEST.txt", manifest(out));
        return out;
    }

    private String manifest(Map<String, String> files) {
        StringBuilder sb = new StringBuilder();
        sb.append("model=").append(model.name()).append('\n');
        sb.append("revision=").append(version).append('\n');
        sb.append("fingerprint=").append(fingerprint).append('\n');
        for (String path : files.keySet()) sb.append("file=").append(path).append('\n');
        return sb.toString();
    }

    static String sanitize(String s) {
        return s.toLowerCase().replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "");
    }

    static String upper(String s) {
        return s.toUpperCase().replaceAll("[^A-Z0-9]+", "_");
    }

    static String lower(String s) {
        return sanitize(s);
    }

    /** Traceability tag attached to every mask and write sequence. */
    static String trace(ChipModel m, String version, String fp) {
        return "trace: model " + m.name() + " rev " + version + " fp " + fp.substring(0, 12);
    }
}
