package com.regionalapi.network;

import com.google.common.hash.Hashing;
import com.regionalapi.RegionalModConstants;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Anti-Seed-Cracking network payload transmitted to clients on login for client-side minimap
 * and HUD integrations.
 * <p>
 * Never transmits the raw {@code WorldSeed}. Instead, transmits a salted SHA-256 64-bit digest:
 * <pre>
 * long regionalSeed = Hashing.sha256().hashLong(worldSeed ^ 0x5DEECE66DL).asLong();
 * </pre>
 */
public record ClientboundSyncRegionalSeedPacket(long regionalSeed) implements CustomPacketPayload {

    public static final long SEED_SALT = 0x5DEECE66DL;

    public static final CustomPacketPayload.Type<ClientboundSyncRegionalSeedPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(RegionalModConstants.MOD_ID, "sync_regional_seed"));

    public static final StreamCodec<ByteBuf, ClientboundSyncRegionalSeedPacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_LONG,
                    ClientboundSyncRegionalSeedPacket::regionalSeed,
                    ClientboundSyncRegionalSeedPacket::new
            );

    private static volatile long clientRegionalSeed = 0L;

    /**
     * Computes the anti-seed-cracking salted SHA-256 regional sub-seed from the server's raw world seed.
     *
     * @param rawWorldSeed The raw 64-bit server world seed.
     * @return The salted SHA-256 64-bit regional seed.
     */
    public static long computeSaltedRegionalSeed(long rawWorldSeed) {
        return Hashing.sha256().hashLong(rawWorldSeed ^ SEED_SALT).asLong();
    }

    /**
     * Returns the latest synced regional seed on the client.
     */
    public static long getClientRegionalSeed() {
        return clientRegionalSeed;
    }

    /**
     * Sets the synced regional seed on the client side.
     */
    public static void setClientRegionalSeed(long seed) {
        clientRegionalSeed = seed;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
