package com.aid.model.probe.impl;

import org.springframework.stereotype.Component;

/** Read-only H3 task-list probe for the separate official provider. */
@Component
public class MinimaxH3Probe extends MinimaxProbe {

    @Override
    public String providerCode() {
        return "minimax_h3";
    }
}
