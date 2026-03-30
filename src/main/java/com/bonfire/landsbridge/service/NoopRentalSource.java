package com.bonfire.landsbridge.service;

import com.bonfire.landsbridge.model.BridgeRunResult;

public final class NoopRentalSource implements RentalSource {

    @Override
    public String name() {
        return "noop";
    }

    @Override
    public BridgeRunResult runCycle() {
        return BridgeRunResult.empty(name(), "cycle", true, "noop provider");
    }

    @Override
    public BridgeRunResult restore(String landFilter) {
        return BridgeRunResult.empty(name(), "restore", true, "noop provider");
    }
}
