package com.latchandlabel.client.tooltip;

import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemGroups;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.Identifier;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class ItemCategoryMappings {
    private static final Identifier AIR_ITEM_ID = Registries.ITEM.getId(Items.AIR);
    private static boolean flowerTagChecksAvailable = true;
    private static final Set<String> STONE_TOKENS = Set.of(
            "stone", "cobblestone", "deepslate", "tuff", "calcite",
            "andesite", "diorite", "granite", "stone_brick"
    );
    private static final Set<String> STONE_SUFFIXES = Set.of(
            "_slab", "_stairs", "_wall", "_stone", "_bricks"
    );
    private static final Set<String> WOOD_TOKENS = Set.of(
            "oak", "spruce", "birch", "jungle", "acacia", "dark_oak",
            "mangrove", "cherry", "bamboo", "pale_oak"
    );
    private static final Set<String> WOOD_SUFFIXES = Set.of(
            "_log", "_wood", "_planks", "_stairs", "_slab", "_fence", "_fence_gate",
            "_door", "_trapdoor", "_button", "_pressure_plate", "_sign",
            "_hanging_sign", "_boat", "_chest_boat", "_sapling", "_leaves"
    );
    private static final Set<String> TERRAIN_TOKENS = Set.of(
            "dirt", "coarse_dirt", "rooted_dirt", "grass_block", "podzol", "mycelium",
            "sand", "red_sand", "gravel", "clay", "mud", "snow", "ice", "packed_ice", "blue_ice"
    );
    private static final Set<String> NETHER_TOKENS = Set.of(
            "netherrack", "nether", "crimson", "warped", "soul_sand", "soul_soil", "basalt", "quartz"
    );
    private static final Set<String> END_TOKENS = Set.of(
            "end_stone", "purpur", "chorus", "end_rod"
    );
    private static final Set<String> PLANTS_NATURAL_TOKENS = Set.of(
            "leaf", "leaves", "sapling", "flower", "vine", "moss",
            "mushroom", "coral", "kelp", "cactus", "sugar_cane", "azalea", "lily_pad", "seagrass"
    );
    private static final Set<String> GLASS_LIGHT_TOKENS = Set.of(
            "glass", "pane", "lantern", "torch", "glowstone",
            "sea_lantern", "froglight", "candle", "shroomlight", "jack_o_lantern"
    );
    private static final Set<String> DECORATIVE_TOKENS = Set.of(
            "wool", "carpet", "banner", "pot", "item_frame", "painting",
            "bed", "head", "skull", "armor_stand"
    );
    private static final Set<String> FUNCTIONAL_TOKENS = Set.of(
            "chest", "barrel", "shulker_box", "crafting_table", "furnace",
            "smoker", "blast_furnace", "anvil", "enchanting_table", "brewing_stand",
            "stonecutter", "grindstone", "smithing_table", "cartography_table", "loom",
            "lectern", "chiseled_bookshelf", "composter", "jukebox", "lodestone"
    );
    private static final Set<String> FLOWER_FALLBACK_TOKENS = Set.of(
            "flower", "dandelion", "poppy", "orchid", "allium", "azure_bluet",
            "oxeye_daisy", "cornflower", "lily_of_the_valley", "wither_rose", "sunflower",
            "lilac", "rose_bush", "peony", "pink_petals", "spore_blossom", "torchflower"
    );
    private static final Set<String> ORE_VALUABLE_TOKENS = Set.of(
            "_ore", "raw_", "_ingot", "_nugget", "diamond",
            "emerald", "lapis", "coal", "amethyst", "netherite", "ancient_debris"
    );
    private static final Set<String> ORE_VALUABLE_ITEM_IDS = Set.of(
            "minecraft:diamond",
            "minecraft:emerald",
            "minecraft:lapis_lazuli",
            "minecraft:coal",
            "minecraft:amethyst_shard",
            "minecraft:netherite_scrap",
            "minecraft:netherite_ingot"
    );
    private static final Set<String> MOB_DROP_TOKENS = Set.of(
            "string", "bone", "bonemeal", "leather", "slime_ball", "gunpowder",
            "blaze_rod", "blaze_powder", "ender_pearl", "feather", "ink_sac",
            "spider_eye", "rotten_flesh", "ghast_tear", "phantom_membrane"
    );
    private static final Set<String> GEAR_SUFFIXES = Set.of(
            "_sword", "_helmet", "_chestplate", "_leggings", "_boots"
    );
    private static final Set<String> GEAR_TOKENS = Set.of(
            "elytra", "shield", "bow", "crossbow", "trident", "arrow"
    );
    private static final Set<String> FOOD_BREWING_SUFFIXES = Set.of(
            "_stew", "_soup", "_pie", "_apple", "_bread", "_beef",
            "_porkchop", "_mutton", "_chicken", "_fish", "_salmon",
            "_carrot", "_potato", "_beetroot", "_berries", "_cookie",
            "_cake", "_cod", "_rabbit", "_slice", "_kelp", "_bottle"
    );
    private static final Set<String> FOOD_BREWING_TOKENS = Set.of(
            "potion", "nether_wart", "blaze_powder", "fermented_spider_eye",
            "carrot", "potato", "beetroot", "berries", "cookie",
            "cake", "melon_slice", "chorus_fruit", "honey_bottle",
            "dried_kelp", "suspicious_stew", "golden_carrot", "golden_apple"
    );
    private static final Set<String> TOOLS_SUFFIXES = Set.of(
            "_pickaxe", "_axe", "_shovel", "_hoe"
    );
    private static final Set<String> TOOLS_TOKENS = Set.of(
            "compass", "clock", "spyglass", "fishing_rod", "flint_and_steel",
            "bucket", "lead", "name_tag", "shears", "brush"
    );
    private static final Set<String> REDSTONE_TOKENS = Set.of(
            "redstone", "repeater", "comparator", "observer", "piston",
            "dispenser", "dropper", "rail", "daylight_detector", "sculk_sensor",
            "note_block", "target", "tripwire", "slime_block", "honey_block"
    );

    private ItemCategoryMappings() {
    }

    public static Map<Identifier, String> createDefaults() {
        Map<Identifier, String> defaults = new LinkedHashMap<>();

        seedLegacyDefaults(defaults);
        copyCreativeTabMappings(defaults);
        assignAllRegistryItems(defaults);

        return Map.copyOf(defaults);
    }

    private static void seedLegacyDefaults(Map<Identifier, String> defaults) {
        put(defaults, "minecraft:stone", "stones");
        put(defaults, "minecraft:cobblestone", "stones");
        put(defaults, "minecraft:oak_log", "woods");
        put(defaults, "minecraft:spruce_log", "woods");
        put(defaults, "minecraft:dirt", "terrain");
        put(defaults, "minecraft:wheat", "food_brewing");
        put(defaults, "minecraft:bread", "food_brewing");
        put(defaults, "minecraft:iron_pickaxe", "gear_utility");
        put(defaults, "minecraft:redstone", "redstone_mechanisms");
        put(defaults, "minecraft:iron_ore", "ores_valuables");
        put(defaults, "minecraft:iron_ingot", "ores_valuables");
        put(defaults, "minecraft:diamond", "ores_valuables");
        put(defaults, "minecraft:bone", "mob_drops");
        put(defaults, "minecraft:netherrack", "nether");
        put(defaults, "minecraft:end_stone", "end");
        put(defaults, "minecraft:potion", "food_brewing");
        put(defaults, "minecraft:flower_pot", "decorative");
        put(defaults, "minecraft:chest", "functional");
    }

    private static void copyCreativeTabMappings(Map<Identifier, String> defaults) {
        List<Map.Entry<RegistryKey<ItemGroup>, String>> creativeTabToCategory = List.of(
                Map.entry(ItemGroups.FOOD_AND_DRINK, "food_brewing"),
                Map.entry(ItemGroups.COMBAT, "gear_utility"),
                Map.entry(ItemGroups.TOOLS, "gear_utility"),
                Map.entry(ItemGroups.REDSTONE, "redstone_mechanisms"),
                Map.entry(ItemGroups.FUNCTIONAL, "functional"),
                Map.entry(ItemGroups.BUILDING_BLOCKS, "stones"),
                Map.entry(ItemGroups.COLORED_BLOCKS, "decorative"),
                Map.entry(ItemGroups.NATURAL, "plants_natural"),
                Map.entry(ItemGroups.INGREDIENTS, "ores_valuables"),
                Map.entry(ItemGroups.SPAWN_EGGS, "mob_drops"),
                Map.entry(ItemGroups.OPERATOR, "redstone_mechanisms")
        );

        for (Map.Entry<RegistryKey<ItemGroup>, String> creativeEntry : creativeTabToCategory) {
            Identifier groupId = creativeEntry.getKey().getValue();
            ItemGroup group = Registries.ITEM_GROUP.get(groupId);
            if (group == null) {
                continue;
            }

            for (ItemStack stack : group.getSearchTabStacks()) {
                if (stack == null || stack.isEmpty()) {
                    continue;
                }

                Identifier itemId = Registries.ITEM.getId(stack.getItem());
                if (itemId == null || itemId.equals(AIR_ITEM_ID)) {
                    continue;
                }

                defaults.putIfAbsent(itemId, remapCategory(itemId, creativeEntry.getValue()));
            }
        }
    }

    private static void assignAllRegistryItems(Map<Identifier, String> defaults) {
        for (Identifier itemId : Registries.ITEM.getIds()) {
            if (itemId == null || itemId.equals(AIR_ITEM_ID)) {
                continue;
            }

            defaults.putIfAbsent(itemId, remapCategory(itemId, "decorative"));
        }
    }

    private static String remapCategory(Identifier itemId, String baseCategory) {
        String path = itemId.getPath();
        String fullId = itemId.toString();

        String explicitOverride = explicitCategoryOverride(itemId, path);
        if (explicitOverride != null) {
            return explicitOverride;
        }
        if (isFoodBrewing(path)) {
            return "food_brewing";
        }
        if (isGearUtility(path)) {
            return "gear_utility";
        }
        if (isFunctional(path)) {
            return "functional";
        }
        if (isRedstoneMechanism(path)) {
            return "redstone_mechanisms";
        }
        if (isOreOrValuable(path, fullId)) {
            return "ores_valuables";
        }
        if (isMobDrop(path, fullId)) {
            return "mob_drops";
        }
        if (isNether(path)) {
            return "nether";
        }
        if (isEnd(path)) {
            return "end";
        }
        if (isTerrain(path)) {
            return "terrain";
        }
        if (isWoodFamily(path)) {
            return "woods";
        }
        if (isStoneFamily(path)) {
            return "stones";
        }
        if (isFlower(itemId, path)) {
            return "plants_natural";
        }
        if (isPlantsNatural(path)) {
            return "plants_natural";
        }
        if (isGlassLight(path)) {
            return "glass_light";
        }
        if (isDecorative(path)) {
            return "decorative";
        }
        return baseCategory;
    }

    private static String explicitCategoryOverride(Identifier itemId, String path) {
        if (path.endsWith("_button") || path.endsWith("_pressure_plate")) {
            return "redstone_mechanisms";
        }
        if (path.contains("shelf")) {
            return "functional";
        }
        if (path.equals("acacia_wood") || path.equals("acacia_trapdoor")) {
            return "woods";
        }
        if (path.equals("bricks")
                || path.equals("prismarine")
                || path.equals("dark_prismarine")) {
            return "stones";
        }
        if (path.contains("copper") && (path.endsWith("_stairs") || path.endsWith("_slab"))) {
            return "decorative";
        }
        if (path.endsWith("_sapling") || path.equals("mangrove_propagule") || path.equals("bamboo")) {
            return "woods";
        }
        if (isFlower(itemId, path)) {
            return "plants_natural";
        }
        return null;
    }

    private static boolean isFoodBrewing(String path) {
        return containsAny(path, FOOD_BREWING_TOKENS) || endsWithAny(path, FOOD_BREWING_SUFFIXES);
    }

    private static boolean isGearUtility(String path) {
        return containsAny(path, GEAR_TOKENS)
                || containsAny(path, TOOLS_TOKENS)
                || endsWithAny(path, GEAR_SUFFIXES)
                || endsWithAny(path, TOOLS_SUFFIXES);
    }

    private static boolean isFunctional(String path) {
        return containsAny(path, FUNCTIONAL_TOKENS);
    }

    private static boolean isRedstoneMechanism(String path) {
        return containsAny(path, REDSTONE_TOKENS);
    }

    private static boolean isOreOrValuable(String path, String fullId) {
        return containsAny(path, ORE_VALUABLE_TOKENS) || ORE_VALUABLE_ITEM_IDS.contains(fullId);
    }

    private static boolean isMobDrop(String path, String fullId) {
        return fullId.endsWith("_spawn_egg") || containsAny(path, MOB_DROP_TOKENS);
    }

    private static boolean isNether(String path) {
        return containsAny(path, NETHER_TOKENS);
    }

    private static boolean isEnd(String path) {
        return containsAny(path, END_TOKENS);
    }

    private static boolean isTerrain(String path) {
        return containsAny(path, TERRAIN_TOKENS);
    }

    private static boolean isWoodFamily(String path) {
        for (String token : WOOD_TOKENS) {
            if (!path.contains(token)) {
                continue;
            }
            for (String suffix : WOOD_SUFFIXES) {
                if (path.endsWith(suffix)) {
                    return true;
                }
            }
            if (path.startsWith("stripped_") || path.contains("stripped_")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isStoneFamily(String path) {
        return containsAny(path, STONE_TOKENS) || endsWithAny(path, STONE_SUFFIXES);
    }

    private static boolean isPlantsNatural(String path) {
        return containsAny(path, PLANTS_NATURAL_TOKENS);
    }

    private static boolean isFlower(Identifier itemId, String path) {
        if (flowerTagChecksAvailable) {
            try {
                Item item = Registries.ITEM.get(itemId);
                if (item != null && item != Items.AIR && item.getDefaultStack().isIn(ItemTags.FLOWERS)) {
                    return true;
                }
            } catch (IllegalStateException ignored) {
                // During very early client initialization, tags may not be bound yet.
                // Fall back to path heuristics and skip further tag lookups this session.
                flowerTagChecksAvailable = false;
            }
        }
        return containsAny(path, FLOWER_FALLBACK_TOKENS) || path.endsWith("_tulip");
    }

    private static boolean isGlassLight(String path) {
        return containsAny(path, GLASS_LIGHT_TOKENS);
    }

    private static boolean isDecorative(String path) {
        return containsAny(path, DECORATIVE_TOKENS);
    }

    private static boolean containsAny(String value, Set<String> tokens) {
        for (String token : tokens) {
            if (value.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private static boolean endsWithAny(String value, Set<String> suffixes) {
        for (String suffix : suffixes) {
            if (value.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    private static void put(Map<Identifier, String> map, String idRaw, String categoryId) {
        Identifier id = Objects.requireNonNull(Identifier.tryParse(idRaw), "Invalid default item id: " + idRaw);
        map.put(id, categoryId);
    }
}
