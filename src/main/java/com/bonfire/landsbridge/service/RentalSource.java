package com.bonfire.landsbridge.service;

import com.bonfire.landsbridge.model.BridgeRunResult;

public interface RentalSource extends AutoCloseable {

    String name();

    BridgeRunResult runCycle();

    BridgeRunResult restore(String landFilter);

    @Override
    default void close() {
    }
}
