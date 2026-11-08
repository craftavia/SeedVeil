package net.craftavia.seedveil.service;

import com.google.gson.JsonObject;
import io.papermc.paper.datapack.Datapack;
import net.craftavia.seedveil.SeedVeilPlugin;
import net.craftavia.seedveil.Settings;
import net.craftavia.seedveil.datapack.BedrockDatapack;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static net.craftavia.seedveil.Messages.msg;

/** /seedveil status and the startup summary: collects the real state of every layer. */
public final class StatusService {

    private final SeedVeilPlugin plugin;

    public StatusService(final SeedVeilPlugin plugin) {
        this.plugin = plugin;
    }

    /** Call on the main thread; files are read in the background and the result is sent on the main thread. */
    public void report(final CommandSender sender) {
        final List<Component> head = new ArrayList<>();
        final Settings settings = plugin.settings();
        head.add(msg("status.header"));

        if (plugin.hook().active()) {
            head.add(msg("status.hashed-active", Component.text(plugin.hook().rewrittenCount())));
        } else if (!settings.hashedSeed()) {
            head.add(msg("status.hashed-disabled"));
        } else {
            final String reason = plugin.hook().failure();
            head.add(msg("status.hashed-failed", Component.text(reason.isEmpty() ? "?" : reason)));
        }

        // Main-thread part of the data pack check; the manifest file is read in the background.
        final String activePack = activeDatapack(head);
        final int datapackIndex = activePack == null ? -1 : head.size();
        if (activePack != null) {
            head.add(Component.empty());
        }

        if (settings.commandsEnabled() && !settings.blockedCommands().isEmpty()) {
            head.add(msg("status.commands-active", Component.text(settings.blockedCommands().size())));
        } else {
            head.add(msg("status.commands-off"));
        }

        if (settings.monitorEnabled()) {
            head.add(msg("status.monitor-active", Component.text(settings.monitorThreshold()),
                    Component.text(settings.monitorWindowMinutes())));
        } else {
            head.add(msg("status.monitor-off"));
        }

        final boolean geyser = plugin.getServer().getPluginManager().getPlugin("Geyser-Spigot") != null
                || plugin.getServer().getPluginManager().getPlugin("Geyser") != null;
        head.add(msg(geyser ? "status.geyser-present" : "status.geyser-absent"));

        final List<Path> worlds = plugin.worldFolders();
        final ConfigScrambler scrambler = plugin.scrambler();
        final Path dataDir = plugin.getDataFolder().toPath();

        plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
            if (datapackIndex >= 0) {
                final JsonObject manifest = BedrockDatapack.readManifest(dataDir);
                final String files = manifest == null || !manifest.has("files") ? activePack : manifest.get("files").toString();
                head.set(datapackIndex, msg("status.datapack-active", Component.text(files)));
            }
            final List<Component> tail = new ArrayList<>();
            try {
                tail.addAll(fileLines(scrambler.inspect(worlds), settings));
            } catch (final IOException | RuntimeException e) {
                plugin.getSLF4JLogger().warn("Could not read the server config files: {}", e.toString());
                tail.add(msg("status.files-unreadable", Component.text(String.valueOf(e.getMessage()))));
            }
            plugin.getServer().getGlobalRegionScheduler().run(plugin, t -> {
                head.forEach(sender::sendMessage);
                tail.forEach(sender::sendMessage);
            });
        });
    }

    /** Adds a problem line and returns null, or returns the name of the enabled pack (its line is filled in later). */
    private String activeDatapack(final List<Component> lines) {
        if (!plugin.getConfig().getBoolean("bedrock-datapack.enabled", true)) {
            lines.add(msg("status.datapack-disabled"));
            return null;
        }
        Datapack pack = null;
        for (final Datapack candidate : plugin.getServer().getDatapackManager().getPacks()) {
            if (candidate.getName().toLowerCase(Locale.ROOT).contains(BedrockDatapack.PACK_ID)) {
                pack = candidate;
                break;
            }
        }
        if (pack == null) {
            lines.add(msg("status.datapack-missing"));
            return null;
        }
        if (pack.getCompatibility() != Datapack.Compatibility.COMPATIBLE) {
            lines.add(msg("status.datapack-incompatible", Component.text(pack.getCompatibility().name())));
            return null;
        }
        if (!pack.isEnabled()) {
            lines.add(msg("status.datapack-not-enabled", Component.text(pack.getName())));
            return null;
        }
        return pack.getName();
    }

    private static List<Component> fileLines(final ConfigScrambler.Inspection inspection, final Settings settings) {
        final List<Component> lines = new ArrayList<>();
        if (!inspection.spigotFound()) {
            lines.add(msg("status.structures-vanilla", Component.text(inspection.structureKeys()),
                    Component.text(inspection.structureKeys())));
        } else if (inspection.vanillaKeys() == 0) {
            lines.add(msg("status.structures-random", Component.text(inspection.structureKeys())));
        } else {
            lines.add(msg("status.structures-vanilla", Component.text(inspection.vanillaKeys()),
                    Component.text(inspection.structureKeys())));
        }
        for (final String world : inspection.perWorldSections()) {
            lines.add(msg("status.per-world-seeds", Component.text(world)));
        }
        lines.add(msg(inspection.featureSeedsOn() ? "status.feature-on" : "status.feature-off"));
        lines.add(msg(inspection.antiXrayOn() ? "status.antixray-on" : "status.antixray-off"));
        if (inspection.regionFiles() > settings.maxRegionFilesWithoutConfirm()
                && (inspection.vanillaKeys() > 0 || !inspection.featureSeedsOn())) {
            lines.add(msg("status.world-warning", Component.text(inspection.regionFiles())));
        }
        return lines;
    }
}
