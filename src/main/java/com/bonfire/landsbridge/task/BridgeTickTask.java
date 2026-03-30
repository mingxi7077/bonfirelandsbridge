package com.bonfire.landsbridge.task;

import com.bonfire.landsbridge.service.BridgeService;

public final class BridgeTickTask implements Runnable {

    private final BridgeService bridgeService;

    public BridgeTickTask(BridgeService bridgeService) {
        this.bridgeService = bridgeService;
    }

    @Override
    public void run() {
        bridgeService.runCycle();
    }
}
