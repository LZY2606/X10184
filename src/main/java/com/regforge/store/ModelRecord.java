package com.regforge.store;

public record ModelRecord(long id, String name, String version, String contentHash,
                          String canonical, String yaml, String createdAt) {
}
