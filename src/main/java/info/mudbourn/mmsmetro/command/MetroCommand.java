package info.mudbourn.mmsmetro.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import info.mudbourn.mmsmetro.MmsMetro;
import info.mudbourn.mmsmetro.train.Consist;
import info.mudbourn.mmsmetro.train.ConsistManager;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

// The /metro command tree.
public final class MetroCommand {

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("metro")
            .requires(CommandManager.requirePermissionLevel(CommandManager.GAMEMASTERS_CHECK))
            .then(CommandManager.literal("spawn")
                .executes(context -> spawn(context.getSource(), 0))
                .then(CommandManager.argument("cars", IntegerArgumentType.integer(1, 32))
                    .executes(context -> spawn(context.getSource(),
                        IntegerArgumentType.getInteger(context, "cars")))))
            .then(CommandManager.literal("remove")
                .then(CommandManager.literal("all")
                    .executes(context -> removeAll(context.getSource())))
                .then(CommandManager.literal("nearest")
                    .executes(context -> removeNearest(context.getSource()))))
            .then(CommandManager.literal("config")
                .then(CommandManager.argument("key", StringArgumentType.word())
                    .then(CommandManager.argument("value", StringArgumentType.word())
                        .executes(context -> config(context.getSource(),
                            StringArgumentType.getString(context, "key"),
                            StringArgumentType.getString(context, "value")))))));
    }

    private static int spawn(ServerCommandSource source, int cars) {
        ServerWorld world = source.getWorld();
        BlockPos base = BlockPos.ofFloored(source.getPosition());
        BlockPos rail = ConsistManager.findRail(world, base);
        if (rail == null) {
            source.sendError(Text.literal("No rail found under you."));
            return 0;
        }

        ServerPlayerEntity player = source.getPlayer();
        Direction facing = player != null ? player.getHorizontalFacing() : Direction.NORTH;
        int count = cars > 0 ? cars : MmsMetro.config().carsPerTrain;
        Consist consist = ConsistManager.spawn(world, rail, facing, count, MmsMetro.config());
        if (consist == null) {
            source.sendError(Text.literal("Could not resolve a rail path from there."));
            return 0;
        }

        source.sendFeedback(() -> Text.literal("Spawned a " + count + "-car train."), true);
        return 1;
    }

    private static int removeAll(ServerCommandSource source) {
        int removed = ConsistManager.removeAll(source.getWorld());
        source.sendFeedback(() -> Text.literal("Removed " + removed + " train(s)."), true);
        return removed;
    }

    private static int removeNearest(ServerCommandSource source) {
        boolean removed = ConsistManager.removeNearest(source.getWorld(), source.getPosition());
        source.sendFeedback(() -> Text.literal(removed ? "Removed the nearest train." : "No trains to remove."), true);
        return removed ? 1 : 0;
    }

    private static int config(ServerCommandSource source, String key, String value) {
        source.sendFeedback(() -> Text.literal("Config is not implemented yet (" + key + " = " + value + ")."), false);
        return 1;
    }
}
