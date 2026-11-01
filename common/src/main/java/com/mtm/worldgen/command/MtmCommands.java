package com.mtm.worldgen.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mtm.worldgen.MtmWorldgen;
import com.mtm.worldgen.benchmark.GenerationBenchmark;
import com.mtm.worldgen.logic.TerrainLogic;
import com.mtm.worldgen.logic.debug.HeightMapRenderer;
import com.mtm.worldgen.world.MtmGenerators;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/** Debug commands for inspecting generated terrain. Requires permission level 2 (cheats). */
public final class MtmCommands {
    /** Vanilla default flying speed (Abilities#flyingSpeed). */
    public static final float DEFAULT_FLYING_SPEED = 0.05F;
    private static final float MIN_MULTIPLIER = 0.1F;
    private static final float MAX_MULTIPLIER = 50.0F;
    private static final int DEBUG_MAP_SIZE_PX = 1024;
    private static final int DEFAULT_BLOCKS_PER_PIXEL = 8;
    private static final int MAX_BLOCKS_PER_PIXEL = 64;
    private static final int LOCATE_STEP = 64;
    private static final int LOCATE_RADIUS = 16384;

    private MtmCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("mtm")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("flyspeed")
                        .executes(context -> queryFlySpeed(context.getSource()))
                        .then(Commands.literal("reset")
                                .executes(context -> setFlySpeed(context.getSource(), 1.0F)))
                        .then(Commands.argument("multiplier", FloatArgumentType.floatArg(MIN_MULTIPLIER, MAX_MULTIPLIER))
                                .executes(context -> setFlySpeed(context.getSource(),
                                        FloatArgumentType.getFloat(context, "multiplier")))))
                .then(Commands.literal("debugmap")
                        .executes(context -> exportDebugMap(context.getSource(), DEFAULT_BLOCKS_PER_PIXEL))
                        .then(Commands.argument("blocksPerPixel", IntegerArgumentType.integer(1, MAX_BLOCKS_PER_PIXEL))
                                .executes(context -> exportDebugMap(context.getSource(),
                                        IntegerArgumentType.getInteger(context, "blocksPerPixel")))))
                .then(Commands.literal("benchmark")
                        .executes(context -> runBenchmark(context.getSource())))
                .then(Commands.literal("locate")
                        .then(Commands.literal("mountain")
                                .executes(context -> locateMountain(context.getSource())))));
    }

    /** Runs the generation benchmark off the server thread and reports the result in chat and the log. */
    private static int runBenchmark(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        source.sendSuccess(() -> Component.translatable("commands.mtm_worldgen.benchmark.started"), false);
        CompletableFuture.supplyAsync(() -> GenerationBenchmark.run(source.getLevel()), Util.backgroundExecutor())
                .whenComplete((result, error) -> server.execute(() -> {
                    if (error != null) {
                        MtmWorldgen.LOGGER.error("Benchmark failed", error);
                        source.sendFailure(Component.translatable("commands.mtm_worldgen.benchmark.failed", error.getMessage()));
                        return;
                    }
                    MtmWorldgen.LOGGER.info("Benchmark: {}", result.summary());
                    source.sendSuccess(() -> Component.literal(result.summary()), false);
                }));
        return 1;
    }

    /**
     * Finds the nearest surface above half the maximum height (a real mountain) around the source, off the server
     * thread, and reports it with clickable coordinates like vanilla /locate.
     */
    private static int locateMountain(CommandSourceStack source) {
        TerrainLogic logic = MtmGenerators.terrainLogic(source.getLevel());
        if (logic == null) {
            source.sendFailure(Component.translatable("commands.mtm_worldgen.not_mtm_world"));
            return 0;
        }
        MinecraftServer server = source.getServer();
        Vec3 position = source.getPosition();
        double minHeight = logic.settings().general.maxSurfaceHeight / 2.0;
        CompletableFuture.supplyAsync(() -> logic.findNearestAbove(minHeight, (int) Math.floor(position.x),
                        (int) Math.floor(position.z), LOCATE_STEP, LOCATE_RADIUS), Util.backgroundExecutor())
                .whenComplete((found, error) -> server.execute(() -> {
                    if (error != null || found == null) {
                        source.sendFailure(Component.translatable("commands.mtm_worldgen.locate.mountain.none", LOCATE_RADIUS));
                        return;
                    }
                    int y = (int) Math.ceil(logic.sampleSurfaceHeight(found.x(), found.z())) + 2;
                    int distance = (int) Math.round(Math.hypot(found.x() - position.x, found.z() - position.z));
                    Component coordinates = ComponentUtils.wrapInSquareBrackets(
                                    Component.translatable("chat.coordinates", found.x(), y, found.z()))
                            .withStyle(style -> style.withColor(ChatFormatting.GREEN)
                                    .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND,
                                            "/tp @s " + found.x() + " " + y + " " + found.z()))
                                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                            Component.translatable("chat.coordinates.tooltip"))));
                    source.sendSuccess(() -> Component.translatable("commands.mtm_worldgen.locate.mountain.found",
                            coordinates, distance), false);
                }));
        return 1;
    }

    /** Renders the terrain logic around the source position to a PNG in the world folder, off the server thread. */
    private static int exportDebugMap(CommandSourceStack source, int blocksPerPixel) {
        MinecraftServer server = source.getServer();
        TerrainLogic logic = MtmGenerators.terrainLogic(source.getLevel());
        if (logic == null) {
            source.sendFailure(Component.translatable("commands.mtm_worldgen.not_mtm_world"));
            return 0;
        }
        Vec3 position = source.getPosition();
        int centerX = (int) Math.floor(position.x);
        int centerZ = (int) Math.floor(position.z);
        Path file = server.getWorldPath(LevelResource.ROOT).resolve(MtmWorldgen.MOD_ID).resolve("debug")
                .resolve(String.format(Locale.ROOT, "height_%d_%d_%dbpp.png", centerX, centerZ, blocksPerPixel));

        source.sendSuccess(() -> Component.translatable("commands.mtm_worldgen.debugmap.started",
                DEBUG_MAP_SIZE_PX * blocksPerPixel), false);
        CompletableFuture.runAsync(() -> {
            BufferedImage image = HeightMapRenderer.render(logic, centerX, centerZ,
                    DEBUG_MAP_SIZE_PX, blocksPerPixel);
            try {
                Files.createDirectories(file.getParent());
                ImageIO.write(image, "png", file.toFile());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, Util.backgroundExecutor()).whenComplete((ignored, error) -> server.execute(() -> {
            if (error != null) {
                MtmWorldgen.LOGGER.error("Failed to export debug map", error);
                source.sendFailure(Component.translatable("commands.mtm_worldgen.debugmap.failed", error.getMessage()));
            } else {
                source.sendSuccess(() -> Component.translatable("commands.mtm_worldgen.debugmap.done",
                        file.toAbsolutePath().normalize().toString()), false);
            }
        }));
        return 1;
    }

    private static int queryFlySpeed(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        float multiplier = player.getAbilities().getFlyingSpeed() / DEFAULT_FLYING_SPEED;
        source.sendSuccess(() -> Component.translatable("commands.mtm_worldgen.flyspeed.query", formatMultiplier(multiplier)), false);
        return 1;
    }

    private static int setFlySpeed(CommandSourceStack source, float multiplier) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        player.getAbilities().setFlyingSpeed(DEFAULT_FLYING_SPEED * multiplier);
        player.onUpdateAbilities();
        source.sendSuccess(() -> Component.translatable("commands.mtm_worldgen.flyspeed.set", formatMultiplier(multiplier)), true);
        return 1;
    }

    private static String formatMultiplier(float multiplier) {
        return String.format(Locale.ROOT, "%.1f", multiplier);
    }
}
