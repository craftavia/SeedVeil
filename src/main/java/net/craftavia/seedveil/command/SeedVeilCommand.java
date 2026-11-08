package net.craftavia.seedveil.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.craftavia.seedveil.SeedVeilPlugin;

/** /seedveil [status] | scramble [confirm] | reload */
public final class SeedVeilCommand {

    private final SeedVeilPlugin plugin;

    public SeedVeilCommand(final SeedVeilPlugin plugin) {
        this.plugin = plugin;
    }

    public void register(final Commands registrar) {
        final LiteralCommandNode<CommandSourceStack> node = Commands.literal("seedveil")
                .requires(source -> source.getSender().hasPermission(SeedVeilPlugin.PERM_ADMIN))
                .executes(context -> {
                    plugin.status().report(context.getSource().getSender());
                    return Command.SINGLE_SUCCESS;
                })
                .then(Commands.literal("status").executes(context -> {
                    plugin.status().report(context.getSource().getSender());
                    return Command.SINGLE_SUCCESS;
                }))
                .then(Commands.literal("scramble")
                        .executes(context -> {
                            plugin.scramble(context.getSource().getSender(), false);
                            return Command.SINGLE_SUCCESS;
                        })
                        .then(Commands.literal("confirm").executes(context -> {
                            plugin.scramble(context.getSource().getSender(), true);
                            return Command.SINGLE_SUCCESS;
                        })))
                .then(Commands.literal("reload").executes(context -> {
                    plugin.reload(context.getSource().getSender());
                    return Command.SINGLE_SUCCESS;
                }))
                .build();
        registrar.register(node, "World seed protection status and management.");
    }
}
