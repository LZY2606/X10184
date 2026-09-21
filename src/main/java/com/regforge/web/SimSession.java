package com.regforge.web;

import com.regforge.sim.Simulator;
import com.regforge.sim.StepResult;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

class SimSession {
    final String id = UUID.randomUUID().toString().substring(0, 8);
    final long revision;
    final Simulator simulator;
    final List<StepResult> trace = new ArrayList<>();

    SimSession(long revision, Simulator simulator) {
        this.revision = revision;
        this.simulator = simulator;
    }
}
