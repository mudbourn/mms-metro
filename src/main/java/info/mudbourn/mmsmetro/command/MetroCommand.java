package info.mudbourn.mmsmetro.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

// The /metro command tree.
public final class MetroCommand {

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("metro")
            .requires(CommandManager.requirePermissionLevel(CommandManager.GAMEMASTERS_CHECK))
            .then(CommandManager.literal("spawn")
                .executes(context -> spawn(context.getSource(), 1))
                .then(CommandManager.argument("cars", IntegerArgumentType.integer(1, 32))
                    .executes(context -> spawn(context.getSource(),
                        IntegerArgumentType.getInteger(context, "cars")))))
            .then(CommandManager.literal("remove")
                .then(CommandManager.literal("all")
                    .executes(context -> remove(context.getSource(), true)))
                .then(CommandManager.literal("nearest")
                    .executes(context -> remove(context.getSource(), false))))
            .then(CommandManager.literal("config")
                .then(CommandManager.argument("key", StringArgumentType.word())
                    .then(CommandManager.argument("value", StringArgumentType.word())
                        .executes(context -> config(context.getSource(),
                            StringArgumentType.getString(context, "key"),
                            StringArgumentType.getString(context, "value")))))));
    }

    private static int spawn(ServerCommandSource source, int cars) {
        source.sendFeedback(() -> Text.literal("Spawn is not implemented yet (" + cars + " cars)."), false);

        return 1;
    }

    private static int remove(ServerCommandSource source, boolean all) {
        source.sendFeedback(() -> Text.literal("Remove is not implemented yet (" + (all ? "all" : "nearest") + ")."), false);

        return 1;
    }

    private static int config(ServerCommandSource source, String key, String value) {
        source.sendFeedback(() -> Text.literal("Config is not implemented yet (" + key + " = " + value + ")."), false);

        return 1;
    }
}
