package info.mudbourn.mmsmetro.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import info.mudbourn.mmsmetro.MmsMetro;
import info.mudbourn.mmsmetro.block.entity.StationBlockEntity;
import info.mudbourn.mmsmetro.config.MetroConfig;
import info.mudbourn.mmsmetro.train.Consist;
import info.mudbourn.mmsmetro.train.ConsistManager;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.command.CommandSource;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

// The /metro command tree.
public final class MetroCommand {

    // How far the player's crosshair reaches when targeting a station block.
    private static final double STATION_REACH = 6.0;

    // Suggested values for the free-text exit-direction field.
    private static final java.util.List<String> EXIT_SUGGESTIONS =
        java.util.List.of("left", "right", "both");

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
                .executes(context -> showConfig(context.getSource()))
                .then(CommandManager.argument("key", StringArgumentType.word())
                    .suggests((ctx, builder) -> CommandSource.suggestMatching(MetroConfig.KEYS, builder))
                    .then(CommandManager.argument("value", StringArgumentType.word())
                        .executes(context -> config(context.getSource(),
                            StringArgumentType.getString(context, "key"),
                            StringArgumentType.getString(context, "value"))))))
            .then(buildStationTree()));
    }

    // The /metro station ... subtree, editing the block the player looks at; text fields take the rest of the line so names may contain spaces.
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource> buildStationTree() {
        return CommandManager.literal("station")
            .executes(context -> stationInfo(context.getSource()))
            .then(CommandManager.literal("info")
                .executes(context -> stationInfo(context.getSource())))
            .then(stringField("name", StationBlockEntity::setStationName))
            .then(stringField("line", StationBlockEntity::setLineName))
            .then(stringField("direction", StationBlockEntity::setLineDirection))
            .then(stringField("next", StationBlockEntity::setNextStation))
            .then(stringField("transfer", StationBlockEntity::setTransferLine))
            .then(CommandManager.literal("exit")
                .then(CommandManager.argument("value", StringArgumentType.greedyString())
                    .suggests((ctx, builder) -> CommandSource.suggestMatching(EXIT_SUGGESTIONS, builder))
                    .executes(context -> applyStation(context.getSource(), station ->
                        station.setExitDirection(StringArgumentType.getString(context, "value")),
                        "exit"))))
            .then(CommandManager.literal("dwell")
                .then(CommandManager.argument("ticks", IntegerArgumentType.integer(0, 24000))
                    .executes(context -> applyStation(context.getSource(), station ->
                        station.setDwellTicks(IntegerArgumentType.getInteger(context, "ticks")),
                        "dwell"))))
            .then(CommandManager.literal("terminus")
                .then(CommandManager.argument("value", BoolArgumentType.bool())
                    .executes(context -> applyStation(context.getSource(), station ->
                        station.setTerminus(BoolArgumentType.getBool(context, "value")),
                        "terminus"))))
            .then(CommandManager.literal("hub")
                .then(CommandManager.argument("value", BoolArgumentType.bool())
                    .executes(context -> applyStation(context.getSource(), station ->
                        station.setHub(BoolArgumentType.getBool(context, "value")),
                        "hub"))))
            .then(CommandManager.literal("fixeddir")
                .then(CommandManager.argument("value", BoolArgumentType.bool())
                    .executes(context -> applyStation(context.getSource(), station ->
                        station.setFixedDirection(BoolArgumentType.getBool(context, "value")),
                        "fixeddir"))));
    }

    // A text station field taking the remainder of the command line.
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource> stringField(
            String name, java.util.function.BiConsumer<StationBlockEntity, String> setter) {
        return CommandManager.literal(name)
            .then(CommandManager.argument("value", StringArgumentType.greedyString())
                .executes(context -> applyStation(context.getSource(), station ->
                    setter.accept(station, StringArgumentType.getString(context, "value")),
                    name)));
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

    private static int showConfig(ServerCommandSource source) {
        source.sendFeedback(() -> Text.literal("Metro config: " + MmsMetro.config().describe()), false);
        return 1;
    }

    private static int config(ServerCommandSource source, String key, String value) {
        if (!MmsMetro.config().set(key, value)) {
            source.sendError(Text.literal("Unknown config key or bad value: " + key + " = " + value + "."));
            return 0;
        }
        source.sendFeedback(() -> Text.literal("Set " + key + " = " + value + "."), true);
        return 1;
    }

    // Resolves the station the player is looking at and hands it to an editor, reporting success or why no station could be found.
    private static int applyStation(ServerCommandSource source,
                                    java.util.function.Consumer<StationBlockEntity> editor, String field) {
        StationBlockEntity station = targetedStation(source);
        if (station == null) {
            source.sendError(Text.literal("Look at a station block to edit it."));
            return 0;
        }
        editor.accept(station);
        source.sendFeedback(() -> Text.literal("Updated station " + field + "."), false);
        return 1;
    }

    private static int stationInfo(ServerCommandSource source) {
        StationBlockEntity s = targetedStation(source);
        if (s == null) {
            source.sendError(Text.literal("Look at a station block to inspect it."));
            return 0;
        }
        String info = "Station: name=" + s.getStationName()
            + ", line=" + s.getLineName()
            + ", direction=" + s.getLineDirection()
            + ", fixeddir=" + s.isFixedDirection()
            + ", next=" + s.getNextStation()
            + ", exit=" + s.getExitDirection()
            + ", dwell=" + s.getDwellTicks()
            + ", terminus=" + s.isTerminus()
            + ", hub=" + s.isHub()
            + ", transfer=" + s.getTransferLine();
        source.sendFeedback(() -> Text.literal(info), false);
        return 1;
    }

    // The station block under the player's crosshair, or null if none is there.
    private static StationBlockEntity targetedStation(ServerCommandSource source) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            return null;
        }
        HitResult hit = player.raycast(STATION_REACH, 1.0f, false);
        if (!(hit instanceof BlockHitResult block) || hit.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        BlockEntity be = player.getEntityWorld().getBlockEntity(block.getBlockPos());
        return be instanceof StationBlockEntity station ? station : null;
    }
}
