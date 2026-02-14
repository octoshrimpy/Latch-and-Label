package com.latchandlabel.client.find;

import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

public final class VariantMatcher {
    private static final String[] VARIANT_SUFFIXES = {
            "_stairs",
            "_slab",
            "_wall",
            "_fence",
            "_fence_gate",
            "_button",
            "_pressure_plate"
    };

    public VariantMatchResult resolve(Item target) {
        Objects.requireNonNull(target, "target");

        Set<Item> matches = new LinkedHashSet<>();
        matches.add(target);

        if (!FindSettings.variantMatchingEnabled() || !(target instanceof BlockItem)) {
            return new VariantMatchResult(Set.copyOf(matches), false);
        }

        Identifier targetId = Registries.ITEM.getId(target);
        if (targetId == null) {
            return new VariantMatchResult(Set.copyOf(matches), false);
        }

        String path = targetId.getPath();
        String root = stripKnownSuffix(path);

        boolean usedVariants = false;
        for (String suffix : VARIANT_SUFFIXES) {
            Identifier candidateId = Identifier.tryParse(targetId.getNamespace() + ":" + root + suffix);
            if (candidateId == null || !Registries.ITEM.containsId(candidateId)) {
                continue;
            }

            Item candidate = Registries.ITEM.get(candidateId);
            if (candidate == target) {
                continue;
            }
            usedVariants = true;
            matches.add(candidate);
        }

        return new VariantMatchResult(Set.copyOf(matches), usedVariants);
    }

    private static String stripKnownSuffix(String path) {
        for (String suffix : VARIANT_SUFFIXES) {
            if (path.endsWith(suffix) && path.length() > suffix.length()) {
                return path.substring(0, path.length() - suffix.length());
            }
        }
        return path;
    }

    public record VariantMatchResult(Set<Item> matchSet, boolean usedVariants) {
    }
}
