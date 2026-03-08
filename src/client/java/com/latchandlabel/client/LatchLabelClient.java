package com.latchandlabel.client;

import com.latchandlabel.client.LatchLabel;
import net.fabricmc.api.ClientModInitializer;

/** Fabric client entrypoint that bootstraps global state and registers event hooks. */
public final class LatchLabelClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        LatchLabelClientState.initialize();
        ClientHooks.register();
        LatchLabel.LOGGER.info("{} client initialized", LatchLabel.MOD_NAME);
    }
}
