package com.regmold.sim;

import java.util.List;
import java.util.Map;

public record SimStep(int index, String op, String register, String value,
                      List<String> events, Map<String, String> stateAfter) {}
