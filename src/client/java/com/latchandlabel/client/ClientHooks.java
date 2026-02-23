package com.latchandlabel.client;

import com.latchandlabel.client.input.ClientInputHandler;
import com.latchandlabel.client.input.AltClickMoveToStorageHandler;
import com.latchandlabel.client.tagging.ContainerInteractionTracker;
import com.latchandlabel.client.tagging.ShulkerItemCategoryBridge;
import com.latchandlabel.client.tagging.StorageTagReconciler;
import com.latchandlabel.client.ui.ContainerTagButtonManager;
import com.latchandlabel.client.find.FindCommand;
import com.latchandlabel.client.find.FindHighlightRenderer;
import com.latchandlabel.client.find.FindOverlayListHud;
import com.latchandlabel.client.find.FindScanService;
import com.latchandlabel.client.inspect.FocusedTagBillboardRenderer;
import com.latchandlabel.client.inspect.InspectModeRenderer;
import com.latchandlabel.client.config.ConfigCommand;
import com.latchandlabel.client.data.TagScopeResolver;
import com.latchandlabel.client.tooltip.ItemCategoryTooltipHandler;
import com.latchandlabel.client.LatchLabel;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;

public final class ClientHooks {
    private ClientHooks() {
    }

    public static void register() {
        ClientInputHandler.register();
        AltClickMoveToStorageHandler.register();
        ContainerInteractionTracker.register();
        ShulkerItemCategoryBridge.register();
        FindOverlayListHud.register();
        FocusedTagBillboardRenderer.registerHud();
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            TagScopeResolver.ResolvedScope resolvedScope = TagScopeResolver.resolveCurrentScope(client);
            LatchLabelClientState.dataManager().setActiveScopeId(resolvedScope.primaryScopeId());
            LatchLabelClientState.tagStore().setActiveScopeId(
                    resolvedScope.primaryScopeId(),
                    resolvedScope.fallbackReadScopeIds()
            );
        });
        ClientTickEvents.END_CLIENT_TICK.register(FindScanService::onClientTick);
        ClientTickEvents.END_CLIENT_TICK.register(StorageTagReconciler::onClientTick);
        ClientTickEvents.END_CLIENT_TICK.register(ShulkerItemCategoryBridge::onClientTick);

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            FindCommand.register(dispatcher);
            ConfigCommand.register(dispatcher);
        });

        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            ContainerTagButtonManager.addIfSupported(client, screen, scaledWidth, scaledHeight);
        });

        ItemTooltipCallback.EVENT.register((stack, context, type, lines) -> {
            ItemCategoryTooltipHandler.appendCategoryLine(stack, lines);
        });

        WorldRenderEvents.AFTER_ENTITIES.register(context -> {
            InspectModeRenderer.render(context);
            FocusedTagBillboardRenderer.renderWorld(context);
            FindHighlightRenderer.render(context);
        });

        LatchLabel.LOGGER.info("Registered client API event hooks");
    }
}
