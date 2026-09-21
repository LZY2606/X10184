package com.regforge.gen;

import com.regforge.model.Canonical;
import com.regforge.model.RegisterModel;

public interface CodeGenerator {
    GenBundle generate(RegisterModel model, long revision);

    default String fingerprint(RegisterModel model, long revision) {
        return "model=" + model.getName() + " version=" + model.getVersion()
                + " revision=" + revision + " sha256=" + Canonical.hash(model);
    }
}
