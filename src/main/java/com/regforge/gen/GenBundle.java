package com.regforge.gen;

import java.util.List;

/** All files produced for one model revision, plus the model fingerprint they trace back to. */
public record GenBundle(String modelName, String modelVersion, String contentHash,
                        List<GeneratedFile> files) {
}
