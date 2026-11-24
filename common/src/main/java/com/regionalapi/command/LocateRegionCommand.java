package com.regionalapi.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.regionalapi.RegionalModConstants;
import com.regionalapi.core.math.VoronoiEngine2D;
import com.regionalapi.core.region.RegionContentRegistry;
import com.regionalapi.core.region.RegionDefinition;
import com.regionalapi.core.region.RegionRegistry;
import com.regionalapi.core.search.RegionSpatialLocator;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.commands.arguments.ResourceOrTagArgument;
import net.minecraft.commands.arguments.ResourceOrTagKeyArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.Structure;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Implements the {@code /locate region} command tree:
 * <ul>
 *     <li>{@code /locate region <region_id>}</li>
 *     <li>{@code /locate region <region_id> biome <biome_id | #biome_tag>}</li>
 *     <li>{@code /locate region <region_id> structure <structure_id | #structure_tag>}</li>
 * </ul>
 * <p>
 * Seamlessly integrates into Minecraft's vanilla {@code /locate} command hierarchy with
 * region-scoped server-authoritative tab-completion:
 * <ul>
 *     <li>If the target region has registered structures via {@link com.regionalapi.api.RegionalApi},
 *     only those region-registered structures and tags are suggested.</li>
 *     <li>If the target region has registered biomes or a dual {@link com.regionalapi.core.region.BiomeMaskRule},
 *     only those region biomes and tags are suggested.</li>
 *     <li>Otherwise, suggestions automatically fall back to Minecraft's global biome/structure registries.</li>
 * </ul>
 */
public final class LocateRegionCommand {

    private static final DynamicCommandExceptionType ERROR_STRUCTURE_INVALID = new DynamicCommandExceptionType(
            id -> Component.translatableWithFallback(
                    "commands.locate.region.structure.invalid",
                    "There is no structure with type \"%s\"",
                    id
            )
    );

    private LocateRegionCommand() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    /**
     * Registers the {@code region} subcommand tree onto the vanilla {@code /locate} dispatcher.
     *
     * @param dispatcher   The server command dispatcher.
     * @param buildContext The command build context providing registry lookups for biome/structure arguments.
     */
    public static void register(
            CommandDispatcher<CommandSourceStack> dispatcher,
            CommandBuildContext buildContext
    ) {
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
                                        .executes(context -> executeLocateRegion(
                                                context.getSource(),
                                                ResourceLocationArgument.getId(context, "region")
                                        ))
                                        .then(Commands.literal("biome")
                                                .then(Commands.argument("biome", ResourceOrTagArgument.resourceOrTag(buildContext, Registries.BIOME))
                                                        .suggests(LocateRegionCommand::suggestBiomesForRegion)
                                                        .executes(context -> executeLocateBiomeInRegion(
                                                                context.getSource(),
                                                                ResourceLocationArgument.getId(context, "region"),
                                                                ResourceOrTagArgument.getResourceOrTag(context, "biome", Registries.BIOME)
                                                        ))
                                                )
                                        )
                                        .then(Commands.literal("structure")
                                                .then(Commands.argument("structure", ResourceOrTagKeyArgument.resourceOrTagKey(Registries.STRUCTURE))
                                                        .suggests(LocateRegionCommand::suggestStructuresForRegion)
                                                        .executes(context -> executeLocateStructureInRegion(
                                                                context.getSource(),
                                                                ResourceLocationArgument.getId(context, "region"),
                                                                ResourceOrTagKeyArgument.getResourceOrTagKey(
                                                                        context,
                                                                        "structure",
                                                                        Registries.STRUCTURE,
                                                                        ERROR_STRUCTURE_INVALID
                                                                )
                                                        ))
                                                )
                                        )
                                )
                        )
        );
    }

    private static CompletableFuture<Suggestions> suggestBiomesForRegion(
            CommandContext<CommandSourceStack> context,
            SuggestionsBuilder builder
    ) {
        Registry<Biome> biomeRegistry = context.getSource().getLevel().registryAccess().registryOrThrow(Registries.BIOME);
        ResourceLocation regionId = tryGetRegionId(context);
        RegionContentRegistry contentRegistry = RegionContentRegistry.getInstance();

        if (regionId != null && (contentRegistry.hasExplicitBiomes(regionId) || contentRegistry.hasDualBiomeMask(regionId))) {
            Set<ResourceLocation> directIds = new LinkedHashSet<>();
            Set<ResourceLocation> tagIds = new LinkedHashSet<>();

            for (ResourceLocation biomeId : contentRegistry.getRegisteredBiomes(regionId)) {
                if (biomeRegistry.containsKey(biomeId)) {
                    directIds.add(biomeId);
                }
            }

            for (TagKey<Biome> biomeTag : contentRegistry.getRegisteredBiomeTags(regionId)) {
                tagIds.add(biomeTag.location());
                Optional<HolderSet.Named<Biome>> tagHolders = biomeRegistry.getTag(biomeTag);
                if (tagHolders.isPresent()) {
                    HolderSet.Named<Biome> named = tagHolders.get();
                    for (int i = 0, size = named.size(); i < size; i++) {
                        named.get(i).unwrapKey().ifPresent(key -> directIds.add(key.location()));
                    }
                }
            }

            if (!tagIds.isEmpty()) {
                SharedSuggestionProvider.suggestResource(tagIds, builder, "#");
            }
            if (!directIds.isEmpty()) {
                SharedSuggestionProvider.suggestResource(directIds, builder);
            }
            return builder.buildFuture();
        }

        return suggestGlobalRegistryWithTags(biomeRegistry, builder);
    }

    private static CompletableFuture<Suggestions> suggestStructuresForRegion(
            CommandContext<CommandSourceStack> context,
            SuggestionsBuilder builder
    ) {
        Registry<Structure> structureRegistry = context.getSource().getLevel().registryAccess().registryOrThrow(Registries.STRUCTURE);
        ResourceLocation regionId = tryGetRegionId(context);
        RegionContentRegistry contentRegistry = RegionContentRegistry.getInstance();

        if (regionId != null && contentRegistry.hasRegisteredStructures(regionId)) {
            Set<ResourceLocation> directIds = new LinkedHashSet<>();
            Set<ResourceLocation> tagIds = new LinkedHashSet<>();

            for (ResourceLocation structureId : contentRegistry.getRegisteredStructures(regionId)) {
                if (structureRegistry.containsKey(structureId)) {
                    directIds.add(structureId);
                }
            }

            for (TagKey<Structure> structureTag : contentRegistry.getRegisteredStructureTags(regionId)) {
                tagIds.add(structureTag.location());
                Optional<HolderSet.Named<Structure>> tagHolders = structureRegistry.getTag(structureTag);
                if (tagHolders.isPresent()) {
                    HolderSet.Named<Structure> named = tagHolders.get();
                    for (int i = 0, size = named.size(); i < size; i++) {
                        named.get(i).unwrapKey().ifPresent(key -> directIds.add(key.location()));
                    }
                }
            }

            if (!tagIds.isEmpty()) {
                SharedSuggestionProvider.suggestResource(tagIds, builder, "#");
            }
            if (!directIds.isEmpty()) {
                SharedSuggestionProvider.suggestResource(directIds, builder);
            }
            return builder.buildFuture();
        }

        return suggestGlobalRegistryWithTags(structureRegistry, builder);
    }

    private static <T> CompletableFuture<Suggestions> suggestGlobalRegistryWithTags(
            Registry<T> registry,
            SuggestionsBuilder builder
    ) {
        Set<ResourceLocation> tagIds = new LinkedHashSet<>();
        Iterator<TagKey<T>> tagIterator = registry.getTagNames().iterator();
        while (tagIterator.hasNext()) {
            tagIds.add(tagIterator.next().location());
        }
        if (!tagIds.isEmpty()) {
            SharedSuggestionProvider.suggestResource(tagIds, builder, "#");
        }
        SharedSuggestionProvider.suggestResource(registry.keySet(), builder);
        return builder.buildFuture();
    }

    private static ResourceLocation tryGetRegionId(CommandContext<CommandSourceStack> context) {
        try {
            return ResourceLocationArgument.getId(context, "region");
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static int executeLocateRegion(CommandSourceStack source, ResourceLocation regionId) {
        short targetRawId = validateRegion(source, regionId);
        if (targetRawId < 0) {
            return 0;
        }

        ServerLevel level = source.getLevel();
        BlockPos origin = BlockPos.containing(source.getPosition());

        long packedTarget = RegionSpatialLocator.findNearestRegionBlockPacked(level, origin, targetRawId);
        if (packedTarget != Long.MIN_VALUE) {
            int targetX = RegionSpatialLocator.unpackX(packedTarget);
            int targetZ = RegionSpatialLocator.unpackZ(packedTarget);
            int targetY = level.getHeight(Heightmap.Types.WORLD_SURFACE, targetX, targetZ);
            BlockPos targetPos = new BlockPos(targetX, targetY, targetZ);

            int dx = targetX - origin.getX();
            int dz = targetZ - origin.getZ();
            int distance = (int) Math.round(Math.sqrt((double) dx * dx + (double) dz * dz));
            return sendRegionSuccess(source, regionId, targetPos, distance);
        }

        source.sendFailure(Component.translatableWithFallback(
                "commands.locate.region.not_found",
                "Could not find region %s within %s blocks",
                regionId.toString(),
                RegionSpatialLocator.MAX_REGION_CELL_RADIUS * (VoronoiEngine2D.getActiveCellSizeQuarts() << 2)
        ));
        return 0;
    }

    private static int executeLocateBiomeInRegion(
            CommandSourceStack source,
            ResourceLocation regionId,
            ResourceOrTagArgument.Result<Biome> biomeArg
    ) {
        short targetRawId = validateRegion(source, regionId);
        if (targetRawId < 0) {
            return 0;
        }

        ServerLevel level = source.getLevel();
        MinecraftServer server = level.getServer();
        BlockPos origin = BlockPos.containing(source.getPosition());
        String requestedName = biomeArg.asPrintable();

        RegionSpatialLocator.locateBiomeInRegionAsync(level, origin, targetRawId, biomeArg)
                .whenComplete((result, throwable) -> {
                    if (!server.isRunning()) {
                        return;
                    }
                    server.execute(() -> {
                        if (!server.isRunning()) {
                            return;
                        }
                        if (throwable != null) {
                            RegionalModConstants.LOGGER.error(
                                    "Error while locating biome {} inside region {}: {}",
                                    requestedName, regionId, throwable.getMessage(), throwable
                            );
                            sendBiomeNotFound(source, requestedName, regionId);
                            return;
                        }
                        if (result.isPresent()) {
                            RegionSpatialLocator.BiomeLocateResult found = result.get();
                            String matchedDisplay = formatMatchedHolder(found.biome(), requestedName);
                            sendBiomeSuccess(source, matchedDisplay, regionId, found.pos(), found.distance());
                        } else {
                            sendBiomeNotFound(source, requestedName, regionId);
                        }
                    });
                });

        return 1;
    }

    private static int executeLocateStructureInRegion(
            CommandSourceStack source,
            ResourceLocation regionId,
            ResourceOrTagKeyArgument.Result<Structure> structureArg
    ) throws CommandSyntaxException {
        short targetRawId = validateRegion(source, regionId);
        if (targetRawId < 0) {
            return 0;
        }

        ServerLevel level = source.getLevel();
        Registry<Structure> structureRegistry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        HolderSet<Structure> holderSet = resolveStructureHolders(structureArg, structureRegistry)
                .orElseThrow(() -> ERROR_STRUCTURE_INVALID.create(structureArg.asPrintable()));

        BlockPos origin = BlockPos.containing(source.getPosition());
        String requestedName = structureArg.asPrintable();

        Optional<RegionSpatialLocator.StructureLocateResult> result =
                RegionSpatialLocator.locateStructureInRegion(level, origin, targetRawId, holderSet);

        if (result.isPresent()) {
            RegionSpatialLocator.StructureLocateResult found = result.get();
            String matchedDisplay = formatMatchedHolder(found.structure(), requestedName);
            sendStructureSuccess(source, matchedDisplay, regionId, found.pos(), found.distance());
            return found.distance();
        }

        sendStructureNotFound(source, requestedName, regionId);
        return 0;
    }

    private static short validateRegion(CommandSourceStack source, ResourceLocation regionId) {
        short targetRawId = RegionRegistry.getInstance().getRawId(regionId);
        if (targetRawId < 0) {
            source.sendFailure(Component.translatableWithFallback(
                    "commands.locate.region.invalid",
                    "Unknown region: %s",
                    regionId.toString()
            ));
        }
        return targetRawId;
    }

    private static Optional<? extends HolderSet.ListBacked<Structure>> resolveStructureHolders(
            ResourceOrTagKeyArgument.Result<Structure> structureArg,
            Registry<Structure> structureRegistry
    ) {
        return structureArg.unwrap().map(
                key -> structureRegistry.getHolder(key).map(HolderSet::direct),
                structureRegistry::getTag
        );
    }

    private static String formatMatchedHolder(Holder<?> holder, String requestedName) {
        String actualId = holder.unwrapKey()
                .map(key -> key.location().toString())
                .orElse(requestedName);
        if (requestedName.startsWith("#")) {
            return actualId + " (" + requestedName + ")";
        }
        return actualId;
    }

    private static Component createCoordinateComponent(int x, String yDisplay, int z, String tpY) {
        return ComponentUtils.wrapInSquareBrackets(
                Component.translatable("chat.coordinates", x, yDisplay, z)
        ).withStyle(style -> style
                .withColor(ChatFormatting.GREEN)
                .withClickEvent(new ClickEvent(
                        ClickEvent.Action.SUGGEST_COMMAND,
                        "/tp @s " + x + " " + tpY + " " + z
                ))
                .withHoverEvent(new HoverEvent(
                        HoverEvent.Action.SHOW_TEXT,
                        Component.translatable("chat.coordinates.tooltip")
                ))
        );
    }

    private static int sendRegionSuccess(
            CommandSourceStack source,
            ResourceLocation regionId,
            BlockPos targetPos,
            int distance
    ) {
        Component coordinateComponent = createCoordinateComponent(targetPos.getX(), "~", targetPos.getZ(), "~");
        source.sendSuccess(() -> Component.translatableWithFallback(
                "commands.locate.region.success",
                "The nearest %s is at %s (%s blocks away)",
                regionId.toString(),
                coordinateComponent,
                distance
        ), false);
        return distance;
    }

    private static void sendBiomeSuccess(
            CommandSourceStack source,
            String biomeName,
            ResourceLocation regionId,
            BlockPos targetPos,
            int distance
    ) {
        String yStr = Integer.toString(targetPos.getY());
        Component coordinateComponent = createCoordinateComponent(targetPos.getX(), yStr, targetPos.getZ(), yStr);
        source.sendSuccess(() -> Component.translatableWithFallback(
                "commands.locate.region.biome.success",
                "The nearest %s in region %s is at %s (%s blocks away)",
                biomeName,
                regionId.toString(),
                coordinateComponent,
                distance
        ), false);
    }

    private static void sendBiomeNotFound(CommandSourceStack source, String biomeName, ResourceLocation regionId) {
        source.sendFailure(Component.translatableWithFallback(
                "commands.locate.region.biome.not_found",
                "Could not find biome %s inside region %s",
                biomeName,
                regionId.toString()
        ));
    }

    private static void sendStructureSuccess(
            CommandSourceStack source,
            String structureName,
            ResourceLocation regionId,
            BlockPos targetPos,
            int distance
    ) {
        Component coordinateComponent = createCoordinateComponent(targetPos.getX(), "~", targetPos.getZ(), "~");
        source.sendSuccess(() -> Component.translatableWithFallback(
                "commands.locate.region.structure.success",
                "The nearest %s in region %s is at %s (%s blocks away)",
                structureName,
                regionId.toString(),
                coordinateComponent,
                distance
        ), false);
    }

    private static void sendStructureNotFound(CommandSourceStack source, String structureName, ResourceLocation regionId) {
        source.sendFailure(Component.translatableWithFallback(
                "commands.locate.region.structure.not_found",
                "Could not find structure %s inside region %s",
                structureName,
                regionId.toString()
        ));
    }
}
