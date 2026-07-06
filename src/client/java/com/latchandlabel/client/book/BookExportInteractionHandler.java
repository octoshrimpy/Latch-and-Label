package com.latchandlabel.client.book;

import com.latchandlabel.client.LatchLabelClientState;
import com.latchandlabel.client.find.FindResultState;
import com.latchandlabel.client.find.FindScanService;
import com.latchandlabel.client.model.ChestKey;
import com.latchandlabel.client.targeting.StorageKeyResolver;
import com.latchandlabel.client.targeting.TrackableStorage;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * While holding a writable book, the nearby tagged chests that would be exported are previewed
 * with the find overlay (always, not only when aiming). Right-clicking a tagged container exports
 * that same nearby group into the book. Cancels the vanilla interaction so the book/chest screen
 * doesn't open.
 */
public final class BookExportInteractionHandler {
    /** Keys currently previewed (we own the find overlay), or null when inactive. */
    private static Set<ChestKey> previewKeys = null;
    /** Find-overlay generation at publish time — lets us detect a real /find taking over. */
    private static long previewGeneration = -1L;

    private BookExportInteractionHandler() {
    }

    public static void register() {
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (!world.isClientSide() || hand != InteractionHand.MAIN_HAND || player == null || hitResult == null) {
                return InteractionResult.PASS;
            }

            Minecraft client = Minecraft.getInstance();
            if (client == null || client.player == null) {
                return InteractionResult.PASS;
            }

            ItemStack held = player.getItemInHand(hand);
            if (!held.is(Items.WRITABLE_BOOK)) {
                return InteractionResult.PASS;
            }

            BlockPos pos = hitResult.getBlockPos();
            if (!TrackableStorage.isTrackableStorage(world.getBlockEntity(pos))) {
                return InteractionResult.PASS;
            }
            Optional<ChestKey> keyOpt = StorageKeyResolver.resolveForWorld(world, pos);
            if (keyOpt.isEmpty() || LatchLabelClientState.tagStore().getTag(keyOpt.get()).isEmpty()) {
                return InteractionResult.PASS;
            }

            // Export the same player-nearby set the preview shows.
            BookExportImportService.ExportResult result = BookExportImportService.exportNearbyToHeldBook(
                    client,
                    LatchLabelClientState.tagStore(),
                    LatchLabelClientState.categoryStore(),
                    LatchLabelClientState.itemCategoryMappingService()
            );
            client.player.sendSystemMessage(result.message());
            return InteractionResult.FAIL;
        });
    }

    /** Drives the always-on preview each tick while a writable book is held. */
    public static void onClientTick(Minecraft client) {
        if (client == null || client.player == null) {
            return;
        }

        if (!client.player.getMainHandItem().is(Items.WRITABLE_BOOK)) {
            stopPreview();
            return;
        }

        Set<ChestKey> nearby = new LinkedHashSet<>(BookExportImportService.nearbyTaggedKeys(client, null));
        if (nearby.isEmpty()) {
            stopPreview();
            return;
        }

        if (previewKeys == null) {
            // Starting a preview: don't clobber a real /find already on screen.
            if (FindResultState.hasActiveResults() && !ownsCurrentOverlay()) {
                return;
            }
            publishPreview(client, nearby);
            return;
        }
        if (!ownsCurrentOverlay()) {
            previewKeys = null; // a real /find took over — leave it alone
            return;
        }
        if (!nearby.equals(previewKeys)) {
            publishPreview(client, nearby);
        }
    }

    private static void publishPreview(Minecraft client, Set<ChestKey> keys) {
        List<FindScanService.FindMatch> matches = new ArrayList<>();
        for (ChestKey key : keys) {
            double distance = Math.sqrt(client.player.distanceToSqr(
                    key.pos().getX() + 0.5, key.pos().getY() + 0.5, key.pos().getZ() + 0.5));
            matches.add(new FindScanService.FindMatch(key, FindScanService.MatchType.KNOWN, distance));
        }
        FindResultState.publish(matches);
        FindResultState.setQueryLabel(Component.translatable("latchlabel.book.export_preview_label"));
        FindResultState.setQueryCategory(null);
        previewKeys = keys;
        previewGeneration = FindResultState.currentGeneration();
    }

    private static void stopPreview() {
        if (previewKeys != null) {
            if (ownsCurrentOverlay()) {
                FindResultState.clear();
            }
            previewKeys = null;
        }
    }

    private static boolean ownsCurrentOverlay() {
        return FindResultState.currentGeneration() == previewGeneration;
    }
}
