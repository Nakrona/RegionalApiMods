package com.regionalapi.command;

import com.mojang.brigadier.CommandDispatcher;
import com.regionalapi.api.RegionalApi;
import com.regionalapi.core.cache.ThreadLocalRegionCache;
import com.regionalapi.core.math.DeterministicHash;
import com.regionalapi.core.math.VoronoiEngine2D;
import com.regionalapi.core.region.RegionDefinition;
import com.regionalapi.core.region.RegionRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Implements the {@code /locate region <region_id>} command.
 * <p>
 * Seamlessly integrates into Minecraft's vanilla {@code /locate} command hierarchy,
 * providing sub-millisecond location discovery, tab completions for registered regions,
 * and clickable green coordinates with distance calculations.
 */
public final class LocateRegionCommand {

    private static final int MAX_SEARCH_CELL_RADIUS = 100; // ~51,200 blocks radius

    private LocateRegionCommand() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    /**
     * Registers the {@code region} subcommand node onto the vanilla {@code /locate} dispatcher.
     *
     * @param dispatcher The server command dispatcher.
     */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("locate")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("region")
                                .then(Commands.argument("region", ResourceLocationArgument.id())
                                        .suggests((context, builder) -> {
                                            for (RegionDefinition def : RegionRegistry.getInstance().getAllDefinitions()) {
                                                builder.suggest(def.id().toString());
                                            }
                                            return builder.buildFuture();
                                        })
                                        .executes(context -> executeLocate(
                                                context.getSource(),
                                                ResourceLocationArgument.getId(context, "region")
                                        ))
                                )
                        )
        );
    }

    private static int executeLocate(CommandSourceStack source, ResourceLocation regionId) {
        RegionRegistry registry = RegionRegistry.getInstance();
        short targetRawId = registry.getRawId(regionId);
        if (targetRawId < 0) {
            source.sendFailure(Component.translatableWithFallback(
                    "commands.locate.region.invalid",
                    "Unknown region: %s",
                    regionId.toString()
            ));
            return 0;
        }

        ServerLevel level = source.getLevel();
        BlockPos origin = BlockPos.containing(source.getPosition());
        int originX = origin.getX();
        int originZ = origin.getZ();

        // Check if the command sender is already standing inside the target region
        short currentRawId = RegionalApi.getInstance().getRegionRawId(level, originX, originZ);
        if (currentRawId == targetRawId) {
            return sendSuccess(source, regionId, origin, 0);
        }

        RegionDefinition targetDef = registry.getByRawId(targetRawId);

        // Case 1: Unique region (isUnique == true) - instantaneous lookup from pre-computed spatial QuadTree
        if (targetDef.isUnique()) {
            long packedCenter = RegionalApi.getInstance().getUniqueRegionCenterBlockPacked(regionId);
            if (packedCenter != Long.MIN_VALUE) {
                int targetX = (int) (packedCenter >> 32);
                int targetZ = (int) packedCenter;
                int targetY = level.getHeight(Heightmap.Types.WORLD_SURFACE, targetX, targetZ);
                BlockPos targetPos = new BlockPos(targetX, targetY, targetZ);
                int dx = targetX - originX;
                int dz = targetZ - originZ;
                int distance = (int) Math.round(Math.sqrt((double) dx * dx + (double) dz * dz));
                return sendSuccess(source, regionId, targetPos, distance);
            }
        }

        // Case 2: Procedural Voronoi tessellation - fast expanding ring search over grid cells
        long worldSeed = ThreadLocalRegionCache.getActiveWorldSeed();
        VoronoiEngine2D.GenerationMode mode = registry.getGenerationMode();
        long maxFiniteRadiusSq = registry.getMaxFiniteRadiusSqQuart();

        int originQuartX = originX >> 2;
        int originQuartZ = originZ >> 2;
        int centerGridX = originQuartX >> VoronoiEngine2D.DEFAULT_CELL_SHIFT;
        int centerGridZ = originQuartZ >> VoronoiEngine2D.DEFAULT_CELL_SHIFT;

        int closestBlockX = Integer.MIN_VALUE;
        int closestBlockZ = Integer.MIN_VALUE;
        double closestDistSq = Double.MAX_VALUE;

        for (int r = 1; r <= MAX_SEARCH_CELL_RADIUS; r++) {
            // Horizontal rows: dz = -r and dz = +r
            for (int dx = -r; dx <= r; dx++) {
                double distSq1 = testCell(worldSeed, centerGridX + dx, centerGridZ - r, targetRawId, mode, maxFiniteRadiusSq, originX, originZ, registry);
                if (distSq1 >= 0.0 && distSq1 < closestDistSq) {
                    closestDistSq = distSq1;
                    int seedQX = getSeedQuartX(worldSeed, centerGridX + dx, centerGridZ - r);
                    int seedQZ = getSeedQuartZ(worldSeed, centerGridX + dx, centerGridZ - r);
                    closestBlockX = seedQX << 2;
                    closestBlockZ = seedQZ << 2;
                }

                double distSq2 = testCell(worldSeed, centerGridX + dx, centerGridZ + r, targetRawId, mode, maxFiniteRadiusSq, originX, originZ, registry);
                if (distSq2 >= 0.0 && distSq2 < closestDistSq) {
                    closestDistSq = distSq2;
                    int seedQX = getSeedQuartX(worldSeed, centerGridX + dx, centerGridZ + r);
                    int seedQZ = getSeedQuartZ(worldSeed, centerGridX + dx, centerGridZ + r);
                    closestBlockX = seedQX << 2;
                    closestBlockZ = seedQZ << 2;
                }
            }

            // Vertical columns: dx = -r and dx = +r (excluding corners already evaluated)
            for (int dz = -r + 1; dz <= r - 1; dz++) {
                double distSq3 = testCell(worldSeed, centerGridX - r, centerGridZ + dz, targetRawId, mode, maxFiniteRadiusSq, originX, originZ, registry);
                if (distSq3 >= 0.0 && distSq3 < closestDistSq) {
                    closestDistSq = distSq3;
                    int seedQX = getSeedQuartX(worldSeed, centerGridX - r, centerGridZ + dz);
                    int seedQZ = getSeedQuartZ(worldSeed, centerGridX - r, centerGridZ + dz);
                    closestBlockX = seedQX << 2;
                    closestBlockZ = seedQZ << 2;
                }

                double distSq4 = testCell(worldSeed, centerGridX + r, centerGridZ + dz, targetRawId, mode, maxFiniteRadiusSq, originX, originZ, registry);
                if (distSq4 >= 0.0 && distSq4 < closestDistSq) {
                    closestDistSq = distSq4;
                    int seedQX = getSeedQuartX(worldSeed, centerGridX + r, centerGridZ + dz);
                    int seedQZ = getSeedQuartZ(worldSeed, centerGridX + r, centerGridZ + dz);
                    closestBlockX = seedQX << 2;
                    closestBlockZ = seedQZ << 2;
                }
            }

            // If a candidate was found in ring r, stop expanding
            if (closestBlockX != Integer.MIN_VALUE) {
                break;
            }
        }

        if (closestBlockX != Integer.MIN_VALUE) {
            int targetY = level.getHeight(Heightmap.Types.WORLD_SURFACE, closestBlockX, closestBlockZ);
            BlockPos targetPos = new BlockPos(closestBlockX, targetY, closestBlockZ);
            int distance = (int) Math.round(Math.sqrt(closestDistSq));
            return sendSuccess(source, regionId, targetPos, distance);
        }

        source.sendFailure(Component.translatableWithFallback(
                "commands.locate.region.not_found",
                "Could not find region %s within %s blocks",
                regionId.toString(),
                MAX_SEARCH_CELL_RADIUS * 512
        ));
        return 0;
    }

    private static double testCell(
            long worldSeed,
            int gx,
            int gz,
            short targetRawId,
            VoronoiEngine2D.GenerationMode mode,
            long maxFiniteRadiusSq,
            int originX,
            int originZ,
            RegionRegistry registry
    ) {
        long cellHash = DeterministicHash.hashGridCell(worldSeed, gx, gz);
        short cellRegionId = registry.selectRegionForCell(cellHash, gx, gz);
        if (cellRegionId != targetRawId) {
            return -1.0;
        }

        int seedQX = (gx << VoronoiEngine2D.DEFAULT_CELL_SHIFT) + VoronoiEngine2D.DEFAULT_JITTER_OFFSET
                + DeterministicHash.extractJitterX(cellHash, VoronoiEngine2D.DEFAULT_JITTER_MASK);
        int seedQZ = (gz << VoronoiEngine2D.DEFAULT_CELL_SHIFT) + VoronoiEngine2D.DEFAULT_JITTER_OFFSET
                + DeterministicHash.extractJitterZ(cellHash, VoronoiEngine2D.DEFAULT_JITTER_MASK);

        short dominant = VoronoiEngine2D.evaluateRegionAtQuart(worldSeed, seedQX, seedQZ, mode, maxFiniteRadiusSq);
        if (dominant != targetRawId) {
            return -1.0;
        }

        int bx = seedQX << 2;
        int bz = seedQZ << 2;
        double dx = (double) bx - originX;
        double dz = (double) bz - originZ;
        return (dx * dx) + (dz * dz);
    }

    private static int getSeedQuartX(long worldSeed, int gx, int gz) {
        long cellHash = DeterministicHash.hashGridCell(worldSeed, gx, gz);
        return (gx << VoronoiEngine2D.DEFAULT_CELL_SHIFT) + VoronoiEngine2D.DEFAULT_JITTER_OFFSET
                + DeterministicHash.extractJitterX(cellHash, VoronoiEngine2D.DEFAULT_JITTER_MASK);
    }

    private static int getSeedQuartZ(long worldSeed, int gx, int gz) {
        long cellHash = DeterministicHash.hashGridCell(worldSeed, gx, gz);
        return (gz << VoronoiEngine2D.DEFAULT_CELL_SHIFT) + VoronoiEngine2D.DEFAULT_JITTER_OFFSET
                + DeterministicHash.extractJitterZ(cellHash, VoronoiEngine2D.DEFAULT_JITTER_MASK);
    }

    private static int sendSuccess(
            CommandSourceStack source,
            ResourceLocation regionId,
            BlockPos targetPos,
            int distance
    ) {
        Component coordinateComponent = ComponentUtils.wrapInSquareBrackets(
                Component.translatable("chat.coordinates", targetPos.getX(), "~", targetPos.getZ())
        ).withStyle(style -> style
                .withColor(ChatFormatting.GREEN)
                .withClickEvent(new ClickEvent(
                        ClickEvent.Action.SUGGEST_COMMAND,
                        "/tp @s " + targetPos.getX() + " ~ " + targetPos.getZ()
                ))
                .withHoverEvent(new HoverEvent(
                        HoverEvent.Action.SHOW_TEXT,
                        Component.translatable("chat.coordinates.tooltip")
                ))
        );

        source.sendSuccess(() -> Component.translatableWithFallback(
                "commands.locate.region.success",
                "The nearest %s is at %s (%s blocks away)",
                regionId.toString(),
                coordinateComponent,
                distance
        ), false);

        return distance;
    }
}
