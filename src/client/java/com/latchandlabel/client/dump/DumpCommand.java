package com.latchandlabel.client.dump;

import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;

public final class DumpCommand {
    private DumpCommand() {
    }

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(
                ClientCommands.literal("dump")
                        .executes(context -> {
                            Minecraft client = context.getSource().getClient();
                            DumpService.start(client);
                            return 1;
                        })
        );
    }
}
