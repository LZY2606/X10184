package com.regmold.parse;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Map;
import java.util.TreeMap;

public final class Canonicalizer {
    private Canonicalizer() {
    }

    @SuppressWarnings("unchecked")
    public static String canonicalYaml(Map<String, Object> root) {
        Object normalized = normalize(root);
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setIndent(2);
        options.setPrettyFlow(true);
        options.setLineBreak(DumperOptions.LineBreak.UNIX);
        options.setSplitLines(false);
        return new Yaml(options).dump(normalized);
    }

    public static String semanticHash(String canonicalYaml) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonicalYaml.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.substring(0, 16);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Object normalize(Object value) {
        if (value instanceof Map<?, ?> map) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getValue() == null) {
                    continue;
                }
                sorted.put(entry.getKey().toString(), normalize(entry.getValue()));
            }
            return sorted;
        }
        if (value instanceof Iterable<?> iterable) {
            java.util.List<Object> out = new ArrayList<>();
            for (Object o : iterable) {
                out.add(normalize(o));
            }
            return out;
        }
        return value;
    }
}
