package net.craftavia.seedveil.listener;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import net.craftavia.seedveil.Messages;
import net.craftavia.seedveil.SeedVeilPlugin;
import net.craftavia.seedveil.Settings;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.generator.structure.GeneratedStructure;
import org.bukkit.generator.structure.Structure;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static net.craftavia.seedveil.Messages.msg;

/**
 * Counts players entering the structure types that seed crackers need.
 * A player who visits many different ones in a short time is reported to staff; nobody is punished.
 * Only runs when a player crosses a chunk border, and only queries the tracked structure types (cheap).
 */
public final class StructureMonitor implements Listener {

    private record Visit(String id, String type, long time) {
    }

    private record Tracked(Structure structure, String type) {
    }

    private final SeedVeilPlugin plugin;
    // Accessed from the main thread only.
    private final Map<UUID, Deque<Visit>> visits = new HashMap<>();
    private final Map<UUID, Long> lastAlert = new HashMap<>();
    // The registry lookup runs once per Settings instance (reload creates a new one).
    private Settings resolvedFor;
    private List<Tracked> resolved = List.of();

    public StructureMonitor(final SeedVeilPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(final PlayerMoveEvent event) {
        final Location from = event.getFrom();
        final Location to = event.getTo();
        if (from.getWorld() == to.getWorld()
                && (from.getBlockX() >> 4) == (to.getBlockX() >> 4)
                && (from.getBlockZ() >> 4) == (to.getBlockZ() >> 4)) {
            return;
        }
        final Settings settings = plugin.settings();
        if (!settings.monitorEnabled() || settings.trackedStructures().isEmpty()) {
            return;
        }
        final Player player = event.getPlayer();
        if (player.hasPermission(SeedVeilPlugin.PERM_BYPASS)) {
            return;
        }
        final World world = to.getWorld();
        final int cx = to.getBlockX() >> 4;
        final int cz = to.getBlockZ() >> 4;
        if (!world.isChunkLoaded(cx, cz)) {
            return;
        }
        // Chunk#getStructures() resolves the starts of ALL structures referencing the chunk (mineshafts, strongholds,
        // ancient cities...), which may load the start chunk synchronously. Only the tracked types are queried.
        Vector position = null;
        for (final Tracked tracked : tracked(settings)) {
            for (final GeneratedStructure structure : world.getStructures(cx, cz, tracked.structure())) {
                if (position == null) {
                    position = to.toVector();
                }
                final BoundingBox box = structure.getBoundingBox();
                if (!box.contains(position)) {
                    continue;
                }
                final String id = world.getKey().asString() + "|" + tracked.type() + "@" + (int) box.getMinX() + "," + (int) box.getMinZ();
                record(player, id, tracked.type(), settings);
            }
        }
    }

    private List<Tracked> tracked(final Settings settings) {
        if (settings != resolvedFor) {
            final Registry<Structure> registry = RegistryAccess.registryAccess().getRegistry(RegistryKey.STRUCTURE);
            final List<Tracked> list = new ArrayList<>();
            for (final String type : settings.trackedStructures()) {
                final NamespacedKey key = NamespacedKey.fromString(type);
                final Structure structure = key == null ? null : registry.get(key);
                if (structure != null) {
                    list.add(new Tracked(structure, type));
                } else {
                    plugin.getSLF4JLogger().warn("Unknown structure type in structure-monitor.structures: {}", type);
                }
            }
            resolved = List.copyOf(list);
            resolvedFor = settings;
        }
        return resolved;
    }

    private void record(final Player player, final String id, final String type, final Settings settings) {
        final long now = System.currentTimeMillis();
        final long windowMs = settings.monitorWindowMinutes() * 60_000L;
        final Deque<Visit> deque = visits.computeIfAbsent(player.getUniqueId(), k -> new ArrayDeque<>());
        while (!deque.isEmpty() && deque.peekFirst().time() < now - windowMs) {
            deque.pollFirst();
        }
        for (final Visit visit : deque) {
            if (visit.id().equals(id)) {
                return;
            }
        }
        deque.addLast(new Visit(id, type, now));
        if (deque.size() < settings.monitorThreshold()) {
            return;
        }
        final Long last = lastAlert.get(player.getUniqueId());
        if (last != null && last > now - windowMs) {
            return;
        }
        lastAlert.put(player.getUniqueId(), now);

        final Set<String> types = new LinkedHashSet<>();
        for (final Visit visit : deque) {
            types.add(visit.type().substring(visit.type().indexOf(':') + 1));
        }
        final Component alert = msg("alert.structure-visits", Component.text(player.getName()),
                Component.text(settings.monitorWindowMinutes()), Component.text(deque.size()),
                Component.text(String.join(", ", types)));
        Bukkit.broadcast(alert, SeedVeilPlugin.PERM_NOTIFY);
        plugin.getSLF4JLogger().warn(Messages.plain(alert));
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        visits.remove(event.getPlayer().getUniqueId());
        lastAlert.remove(event.getPlayer().getUniqueId());
    }
}
