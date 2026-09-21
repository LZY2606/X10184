package com.regmold.gen;

import com.regmold.domain.Model;

import java.util.List;

/** Machine-readable provenance manifest tying every symbol/file to the model version. */
public final class ManifestGenerator {

    private ManifestGenerator() {
    }

    public static GenFile generate(Model model, String modelHash, List<GenFile> files,
                                   List<SymbolInfo> symbols) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"model\": \"").append(escape(model.name())).append("\",\n");
        sb.append("  \"version\": \"").append(escape(model.version())).append("\",\n");
        sb.append("  \"revision\": ").append(model.revisionId()).append(",\n");
        sb.append("  \"sha256\": \"").append(modelHash).append("\",\n");
        sb.append("  \"generator\": \"register-meld-1\",\n");
        sb.append("  \"files\": [\n");
        for (int i = 0; i < files.size(); i++) {
            sb.append("    { \"path\": \"").append(escape(files.get(i).relativePath()))
                    .append("\", \"bytes\": ").append(files.get(i).content().getBytes().length)
                    .append(i + 1 < files.size() ? " },\n" : " }\n");
        }
        sb.append("  ],\n");
        sb.append("  \"symbols\": [\n");
        for (int i = 0; i < symbols.size(); i++) {
            SymbolInfo s = symbols.get(i);
            sb.append("    { \"symbol\": \"").append(escape(s.symbol()))
                    .append("\", \"kind\": \"").append(s.kind())
                    .append("\", \"register\": \"").append(escape(s.register()))
                    .append("\", \"field\": ").append(s.field() == null ? "null" : "\"" + escape(s.field()) + "\"")
                    .append(", \"address\": \"0x").append(Long.toUnsignedString(s.address(), 16))
                    .append("\", \"width_bits\": ").append(s.widthBits())
                    .append(", \"access\": \"").append(s.access())
                    .append("\" }").append(i + 1 < symbols.size() ? "," : "").append('\n');
        }
        sb.append("  ]\n}\n");
        return new GenFile("regmold-manifest.json", sb.toString());
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
