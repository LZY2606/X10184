package com.regforge.yaml;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.regforge.model.AccessMode;
import com.regforge.model.AddressSpace;
import com.regforge.model.ChipModel;
import com.regforge.model.FieldDef;
import com.regforge.model.RegisterDef;
import com.regforge.model.SideEffect;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class YamlModelParser {

    private final ObjectMapper reader = new ObjectMapper(new YAMLFactory());
    private final ObjectMapper canonicalWriter;

    public YamlModelParser() {
        YAMLFactory factory = new YAMLFactory();
        factory.disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER);
        factory.enable(YAMLGenerator.Feature.MINIMIZE_QUOTES);
        canonicalWriter = new ObjectMapper(factory);
        canonicalWriter.enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    }

    public ChipModel parse(String yaml) {
        JsonNode root;
        try {
            root = reader.readTree(yaml);
        } catch (Exception e) {
            throw new ModelParseException("YAML 解析失败: " + e.getMessage(), e);
        }
        if (root == null || !root.isObject()) {
            throw new ModelParseException("顶层必须是 YAML 映射（包含 name / addressSpaces）");
        }
        String name = text(root, "name", "model");
        String endianness = optionalText(root, "endianness").orElse("little");
        if (!List.of("little", "big").contains(endianness)) {
            throw new ModelParseException("endianness 仅支持 little 或 big，实际: " + endianness);
        }
        JsonNode spacesNode = root.get("addressSpaces");
        if (spacesNode == null || !spacesNode.isArray() || spacesNode.isEmpty()) {
            throw new ModelParseException("addressSpaces 必须是非空数组");
        }
        List<AddressSpace> spaces = new ArrayList<>();
        for (JsonNode spaceNode : spacesNode) {
            String spaceName = text(spaceNode, "name", "addressSpaces[].name");
            long base = longAt(spaceNode, "base", spaceName);
            List<RegisterDef> registers = new ArrayList<>();
            JsonNode regs = spaceNode.get("registers");
            if (regs == null || !regs.isArray()) {
                throw new ModelParseException("地址空间 " + spaceName + " 的 registers 必须是数组");
            }
            for (JsonNode regNode : regs) {
                registers.add(parseRegister(regNode, spaceName));
            }
            registers.sort((a, b) -> {
                int c = Long.compare(a.address(), b.address());
                return c != 0 ? c : a.name().compareTo(b.name());
            });
            spaces.add(new AddressSpace(spaceName, base, List.copyOf(registers)));
        }
        spaces.sort((a, b) -> a.name().compareTo(b.name()));
        return new ChipModel(name, endianness, List.copyOf(spaces));
    }

    private RegisterDef parseRegister(JsonNode node, String spaceName) {
        String regName = text(node, "name", spaceName + ".registers[].name");
        long address = longAt(node, "address", regName);
        int width = (int) node.path("width").asLong(32);
        if (width <= 0 || width > 64) {
            throw new ModelParseException("寄存器 " + regName + " 的 width 必须在 1..64，实际: " + width);
        }
        long resetRaw = node.has("reset") ? longAt(node, "reset", regName) : 0L;
        long reset = resetRaw & (width >= 64 ? ~0L : ((1L << width) - 1));
        String wordOrderRaw = optionalText(node, "wordOrder").orElse("LOW_FIRST");
        RegisterDef.WordOrder wordOrder;
        try {
            wordOrder = RegisterDef.WordOrder.valueOf(wordOrderRaw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ModelParseException("寄存器 " + regName + " 的 wordOrder 非法: " + wordOrderRaw);
        }
        String aliasOf = optionalText(node, "aliasOf").orElse(null);
        String lockedBy = optionalText(node, "lockedBy").orElse(null);
        String description = optionalText(node, "description").orElse("");
        Long unlockValue = node.has("unlockValue") ? longAt(node, "unlockValue", regName) : null;

        List<FieldDef> fields = new ArrayList<>();
        JsonNode fieldsNode = node.get("fields");
        if (fieldsNode != null && fieldsNode.isArray()) {
            for (JsonNode f : fieldsNode) {
                fields.add(parseField(f, regName));
            }
            fields.sort((a, b) -> {
                int c = Integer.compare(a.lsb(), b.lsb());
                return c != 0 ? c : a.name().compareTo(b.name());
            });
        }
        List<SideEffect> effects = new ArrayList<>();
        JsonNode effectsNode = node.get("sideEffects");
        if (effectsNode != null && effectsNode.isArray()) {
            for (JsonNode e : effectsNode) {
                effects.add(parseEffect(e, regName));
            }
            effects.sort((a, b) -> {
                int c = a.triggerField().compareTo(b.triggerField());
                if (c != 0) {
                    return c;
                }
                c = a.targetRegister().compareTo(b.targetRegister());
                if (c != 0) {
                    return c;
                }
                return a.targetField().compareTo(b.targetField());
            });
        }
        return new RegisterDef(regName, address, width, reset, wordOrder, aliasOf,
                lockedBy, unlockValue, description, List.copyOf(fields), List.copyOf(effects));
    }

    private FieldDef parseField(JsonNode node, String regName) {
        String fieldName = text(node, "name", regName + ".fields[].name");
        JsonNode lsbNode = node.get("lsb");
        JsonNode msbNode = node.get("msb");
        JsonNode posNode = node.get("bit");
        int lsb;
        int msb;
        if (lsbNode != null && msbNode != null) {
            lsb = (int) asLong(lsbNode, regName + "." + fieldName + ".lsb");
            msb = (int) asLong(msbNode, regName + "." + fieldName + ".msb");
        } else if (lsbNode != null) {
            lsb = (int) asLong(lsbNode, regName + "." + fieldName + ".lsb");
            msb = node.has("width") ? lsb + (int) asLong(node.get("width"), regName + "." + fieldName) - 1 : lsb;
        } else if (posNode != null) {
            lsb = msb = (int) asLong(posNode, regName + "." + fieldName + ".bit");
        } else {
            throw new ModelParseException("位域 " + regName + "." + fieldName + " 缺少 lsb/msb（或 bit）");
        }
        if (msb < lsb) {
            throw new ModelParseException("位域 " + regName + "." + fieldName + " 的 msb(" + msb + ") < lsb(" + lsb + ")");
        }
        AccessMode access = AccessMode.from(optionalText(node, "access").orElse("RW"));
        String description = optionalText(node, "description").orElse("");
        return new FieldDef(fieldName, lsb, msb, access, description);
    }

    private SideEffect parseEffect(JsonNode node, String regName) {
        String triggerField = optionalText(node, "triggerField")
                .orElseThrow(() -> new ModelParseException(
                        "寄存器 " + regName + " 的副作用缺少 triggerField"));
        JsonNode target = node.get("target");
        String targetRegister;
        String targetField;
        if (target != null && target.isTextual()) {
            String[] parts = target.asText().split("\\.", 2);
            if (parts.length != 2) {
                throw new ModelParseException("副作用 target 必须形如 REG.FIELD: " + target.asText());
            }
            targetRegister = parts[0];
            targetField = parts[1];
        } else {
            targetRegister = text(node, "targetRegister", regName + ".sideEffect");
            targetField = text(node, "targetField", regName + ".sideEffect");
        }
        String actionRaw = optionalText(node, "action")
                .or(() -> optionalText(node, "effect"))
                .orElseThrow(() -> new ModelParseException(
                        "副作用 " + regName + "." + triggerField + " 缺少 action"));
        SideEffect.EffectAction action = SideEffect.EffectAction.from(actionRaw);
        Long value = node.has("value") ? asLong(node.get("value"), regName + "." + triggerField + ".value") : null;
        if (action == SideEffect.EffectAction.ASSIGN && value == null) {
            throw new ModelParseException(
                    "副作用 " + regName + "." + triggerField + " 的 ASSIGN 动作必须提供 value");
        }
        String description = optionalText(node, "description").orElse("");
        return new SideEffect(triggerField, targetRegister, targetField, action, value, description);
    }

    /**
     * 规范化输出：固定键顺序、列表按确定规则排序。
     * 同一语义、不同 YAML 键顺序的输入得到字节级一致的结果。
     */
    public String canonicalize(ChipModel model) {
        try {
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("name", model.name());
            root.put("endianness", model.endianness());
            List<Map<String, Object>> spaces = new ArrayList<>();
            for (AddressSpace space : model.addressSpaces()) {
                Map<String, Object> sm = new LinkedHashMap<>();
                sm.put("name", space.name());
                sm.put("base", "0x" + Long.toUnsignedString(space.base(), 16));
                List<Map<String, Object>> regs = new ArrayList<>();
                for (RegisterDef reg : space.registers()) {
                    Map<String, Object> rm = new LinkedHashMap<>();
                    rm.put("name", reg.name());
                    rm.put("address", "0x" + Long.toUnsignedString(reg.address(), 16));
                    rm.put("width", reg.width());
                    rm.put("reset", "0x" + Long.toUnsignedString(reg.reset(), 16));
                    if (reg.width() > 32) {
                        rm.put("wordOrder", reg.wordOrder().name());
                    }
                    if (reg.aliasOf() != null) {
                        rm.put("aliasOf", reg.aliasOf());
                    }
                    if (reg.lockedBy() != null) {
                        rm.put("lockedBy", reg.lockedBy());
                        rm.put("unlockValue", reg.unlockValue() == null ? 0L : reg.unlockValue());
                    }
                    if (!reg.description().isEmpty()) {
                        rm.put("description", reg.description());
                    }
                    List<Map<String, Object>> fms = new ArrayList<>();
                    for (FieldDef f : reg.fields()) {
                        Map<String, Object> fm = new LinkedHashMap<>();
                        fm.put("name", f.name());
                        fm.put("lsb", f.lsb());
                        fm.put("msb", f.msb());
                        fm.put("access", f.access().name());
                        if (!f.description().isEmpty()) {
                            fm.put("description", f.description());
                        }
                        fms.add(fm);
                    }
                    rm.put("fields", fms);
                    List<Map<String, Object>> ems = new ArrayList<>();
                    for (SideEffect e : reg.sideEffects()) {
                        Map<String, Object> em = new LinkedHashMap<>();
                        em.put("triggerField", e.triggerField());
                        em.put("target", e.targetRegister() + "." + e.targetField());
                        em.put("action", e.action().name());
                        if (e.value() != null) {
                            em.put("value", e.value());
                        }
                        if (!e.description().isEmpty()) {
                            em.put("description", e.description());
                        }
                        ems.add(em);
                    }
                    if (!ems.isEmpty()) {
                        rm.put("sideEffects", ems);
                    }
                    regs.add(rm);
                }
                sm.put("registers", regs);
                spaces.add(sm);
            }
            root.put("addressSpaces", spaces);
            ObjectNode node = canonicalWriter.valueToTree(root);
            return canonicalWriter.writeValueAsString(node);
        } catch (Exception e) {
            throw new ModelParseException("规范化失败: " + e.getMessage(), e);
        }
    }

    public String hashOf(ChipModel model) {
        return sha256Short(canonicalize(model));
    }

    public static String sha256Short(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.substring(0, 12);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String text(JsonNode node, String key, String what) {
        JsonNode value = node.get(key);
        if (value == null || value.isNull()) {
            throw new ModelParseException("缺少必填键 " + key + "（" + what + "）");
        }
        return value.asText();
    }

    private static java.util.Optional<String> optionalText(JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value == null || value.isNull()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(value.asText());
    }

    private static long asLong(JsonNode node, String what) {
        if (node == null || node.isNull()) {
            throw new ModelParseException("缺少数值（" + what + "）");
        }
        if (node.isNumber()) {
            return node.asLong();
        }
        String raw = node.asText().trim();
        try {
            if (raw.startsWith("0x") || raw.startsWith("0X")) {
                return Long.parseUnsignedLong(raw.substring(2), 16);
            }
            if (raw.startsWith("0b") || raw.startsWith("0B")) {
                return Long.parseUnsignedLong(raw.substring(2), 2);
            }
            return Long.parseUnsignedLong(raw);
        } catch (NumberFormatException e) {
            throw new ModelParseException("非法数值 \"" + raw + "\"（" + what + "）");
        }
    }

    private static long longAt(JsonNode node, String key, String what) {
        return asLong(node.get(key), what + "." + key);
    }
}
