package com.regmold.gen;

import java.util.List;

/** A generated public symbol and what model element it came from. */
public record SymbolInfo(String symbol, String kind, String register, String field,
                         long address, Integer widthBits, String access, List<String> languages) {
}
