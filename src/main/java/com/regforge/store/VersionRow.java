package com.regforge.store;

import java.time.Instant;

public record VersionRow(
        long id,
        String name,
        String hash,
        String yaml,
        String canonical,
        String diagnostics,
        Instant createdAt) {
}
