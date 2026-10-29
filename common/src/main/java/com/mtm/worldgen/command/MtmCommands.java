package com.mtm.worldgen.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.TranslatableComponent;
import net.minecraft.server.level.ServerPlayer;

import java.util.Locale;

/** Debug commands for inspecting generated terrain. Requires permission level 2 (cheats). */
public final class MtmCommands {
    /** Vanilla default flying speed (Abilities#flyingSpeed). */
    public static final float DEFAULT_FLYING_SPEED = 0.05F;
    private static final float MIN_MULTIPLIER = 0.1F;
    private static final float MAX_MULTIPLIER = 50.0F;

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
                                        FloatArgumentType.getFloat(context, "multiplier"))))));
    }

    private static int queryFlySpeed(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        float multiplier = player.getAbilities().getFlyingSpeed() / DEFAULT_FLYING_SPEED;
        source.sendSuccess(new TranslatableComponent("commands.mtm_worldgen.flyspeed.query", formatMultiplier(multiplier)), false);
        return 1;
    }

    private static int setFlySpeed(CommandSourceStack source, float multiplier) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        player.getAbilities().setFlyingSpeed(DEFAULT_FLYING_SPEED * multiplier);
        player.onUpdateAbilities();
        source.sendSuccess(new TranslatableComponent("commands.mtm_worldgen.flyspeed.set", formatMultiplier(multiplier)), true);
        return 1;
    }

    private static String formatMultiplier(float multiplier) {
        return String.format(Locale.ROOT, "%.1f", multiplier);
    }
}
