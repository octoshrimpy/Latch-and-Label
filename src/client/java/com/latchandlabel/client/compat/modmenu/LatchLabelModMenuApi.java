package com.latchandlabel.client.compat.modmenu;

import com.latchandlabel.client.config.ui.LatchLabelConfigScreen;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

public final class LatchLabelModMenuApi implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return LatchLabelConfigScreen::new;
    }
}
