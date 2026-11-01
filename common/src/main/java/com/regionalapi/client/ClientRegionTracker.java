package com.regionalapi.client;

import com.regionalapi.api.RegionalApi;
import com.regionalapi.core.region.RegionRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * Client-side tracker storing the authoritative player region and providing
 * debug string formatting for the F3 debug screen.
 */
public final class ClientRegionTracker {

    private static volatile ResourceLocation currentRegionId = RegionRegistry.FALLBACK_WILDS_LOCATION;

    private ClientRegionTracker() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    /**
     * Updates the local player's current authoritative region identifier.
     *
     * @param regionId The new {@link ResourceLocation} region ID.
     */
    public static void setCurrentRegion(ResourceLocation regionId) {
        currentRegionId = (regionId != null) ? regionId : RegionRegistry.FALLBACK_WILDS_LOCATION;
    }

    /**
     * Retrieves the current cached region identifier on the client.
     */
    public static ResourceLocation getCurrentRegion() {
        return currentRegionId;
    }

    /**
     * Formats the debug line for the F3 overlay (e.g. {@code "Region: regionalapi:wilds"}).
     * <p>
     * In singleplayer, evaluates the exact camera block position in real time using the integrated server.
     * In multiplayer, uses the authoritative synced region identifier.
     *
     * @param minecraft The Minecraft client instance.
     * @param cameraPos The current block position of the camera.
     * @return Formatted string for the F3 debug screen.
     */
    public static String getDebugRegionString(Minecraft minecraft, BlockPos cameraPos) {
        BlockPos targetPos = (minecraft != null && minecraft.player != null) ? minecraft.player.blockPosition() : cameraPos;
        if (minecraft != null && minecraft.hasSingleplayerServer() && minecraft.level != null && targetPos != null) {
            MinecraftServer server = minecraft.getSingleplayerServer();
            if (server != null) {
                ServerLevel serverLevel = server.getLevel(minecraft.level.dimension());
                if (serverLevel != null) {
                    ResourceLocation localRegion = RegionalApi.getInstance().getRegionId(
                            serverLevel,
                            targetPos.getX(),
                            targetPos.getZ()
                    );
                    if (localRegion != null && !RegionRegistry.FALLBACK_WILDS_LOCATION.equals(localRegion)) {
                        return "Region: " + localRegion;
                    }
                }
            }
        }

        ResourceLocation id = (currentRegionId != null) ? currentRegionId : RegionRegistry.FALLBACK_WILDS_LOCATION;
        return "Region: " + id;
    }
}
