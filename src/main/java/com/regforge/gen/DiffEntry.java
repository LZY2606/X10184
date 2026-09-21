package com.regforge.gen;

import java.util.List;

/**
 * One difference between two model revisions.
 * @param kind ABI or BEHAVIOR
 * @param change ADDED | REMOVED | CHANGED
 * @param symbol generated-API symbol affected (so renames are never silent)
 * @param detail human-readable description
 * @param impactedApis concrete generated entry points impacted
 */
public record DiffEntry(String kind, String change, String symbol, String detail, List<String> impactedApis) {
}
