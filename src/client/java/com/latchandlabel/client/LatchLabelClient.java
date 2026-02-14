package com.latchandlabel.client;

import com.latchandlabel.client.LatchLabel;
import net.fabricmc.api.ClientModInitializer;

public final class LatchLabelClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        LatchLabelClientState.initialize();
        ClientHooks.register();
        LatchLabel.LOGGER.info("{} client initialized", LatchLabel.MOD_NAME);
    }
}
