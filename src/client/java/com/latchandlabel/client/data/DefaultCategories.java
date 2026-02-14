package com.latchandlabel.client.data;

import com.latchandlabel.client.model.Category;
import net.minecraft.util.Identifier;

import java.util.List;
import java.util.Objects;

public final class DefaultCategories {
    private DefaultCategories() {
    }

    public static List<Category> create() {
        return List.of(
                new Category("stones", "Stones", 0x9D9D97, id("minecraft:stone"), 0, true),
                new Category("woods", "Woods", 0x835432, id("minecraft:oak_log"), 1, true),
                new Category("terrain", "Terrain", 0x5E7C16, id("minecraft:dirt"), 2, true),
                new Category("nether", "Nether", 0xB02E26, id("minecraft:netherrack"), 3, true),
                new Category("end", "End", 0xF9FFFE, id("minecraft:end_stone"), 4, true),
                new Category("plants_natural", "Plants & Natural", 0x80C71F, id("minecraft:oak_sapling"), 5, true),
                new Category("glass_light", "Glass & Light", 0x3AB3DA, id("minecraft:sea_lantern"), 6, true),
                new Category("decorative", "Decorative", 0xC74EBD, id("minecraft:blue_wool"), 7, true),
                new Category("functional", "Functional", 0x169C9C, id("minecraft:chest"), 8, true),
                new Category("redstone_mechanisms", "Redstone & Mechanisms", 0x3C44AA, id("minecraft:redstone"), 9, true),
                new Category("ores_valuables", "Ores & Valuables", 0xFED83D, id("minecraft:diamond"), 10, true),
                new Category("mob_drops", "Mob Drops", 0x474F52, id("minecraft:bone"), 11, true),
                new Category("gear_utility", "Gear & Utility", 0x8932B8, id("minecraft:diamond_pickaxe"), 12, true),
                new Category("food_brewing", "Food & Brewing", 0xF9801D, id("minecraft:potion"), 13, true)
        );
    }

    private static Identifier id(String value) {
        return Objects.requireNonNull(Identifier.tryParse(value), "Invalid identifier literal: " + value);
    }
}
