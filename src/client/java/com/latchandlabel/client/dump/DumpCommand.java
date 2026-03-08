package com.latchandlabel.client.dump;

import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;

public final class DumpCommand {
    private DumpCommand() {
    }

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(
                ClientCommandManager.literal("dump")
                        .executes(context -> {
                            MinecraftClient client = context.getSource().getClient();
                            DumpService.start(client);
                            return 1;
                        })
        );
    }
}
