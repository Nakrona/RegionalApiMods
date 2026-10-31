package com.regionalapi.network;

import com.regionalapi.RegionalModConstants;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server-to-Client network payload transmitted when a player enters or transitions between regions,
 * or upon logging in.
 * <p>
 * Ensures the client-side HUD and F3 debug screen display the exact authoritative region
 * even when connected to dedicated multiplayer servers.
 */
public record ClientboundSyncPlayerRegionPacket(ResourceLocation regionId) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ClientboundSyncPlayerRegionPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(RegionalModConstants.MOD_ID, "sync_player_region"));

    public static final StreamCodec<ByteBuf, ClientboundSyncPlayerRegionPacket> STREAM_CODEC =
            StreamCodec.composite(
                    ResourceLocation.STREAM_CODEC,
                    ClientboundSyncPlayerRegionPacket::regionId,
                    ClientboundSyncPlayerRegionPacket::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
