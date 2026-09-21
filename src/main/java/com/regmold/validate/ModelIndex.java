package com.regmold.validate;

import com.regmold.domain.EffectModel;
import com.regmold.domain.FieldModel;
import com.regmold.domain.Model;
import com.regmold.domain.RegisterModel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ModelIndex {
    public final Model model;
    public final Map<String, RegisterModel> byName = new LinkedHashMap<>();
    public final Map<Long, List<RegisterModel>> byOffset = new LinkedHashMap<>();
    public final Map<String, FieldModel> fieldKey = new LinkedHashMap<>();

    public ModelIndex(Model model) {
        this.model = model;
        for (RegisterModel r : model.registers) {
            byName.put(r.name, r);
            byOffset.computeIfAbsent(r.offset, k -> new ArrayList<>()).add(r);
            for (FieldModel f : r.fields) {
                fieldKey.put(r.name + "." + f.name, f);
            }
        }
    }

    public int width(RegisterModel r) {
        return r.effectiveWidth(model.dataWidth);
    }

    public long fullMask(int width) {
        if (width >= 64) {
            return ~0L;
        }
        return (1L << width) - 1L;
    }

    public FieldModel field(String reg, String field) {
        if (field == null) {
            return null;
        }
        return fieldKey.get(reg + "." + field);
    }

    public List<EffectModel> effectsTriggeredBy(String registerName) {
        List<EffectModel> out = new ArrayList<>();
        for (EffectModel e : model.effects) {
            if (e.triggerRegister.equals(registerName)) {
                out.add(e);
            }
        }
        return out;
    }
}
