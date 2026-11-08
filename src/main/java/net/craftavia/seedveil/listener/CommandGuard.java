package net.craftavia.seedveil.listener;

import net.craftavia.seedveil.Messages;
import net.craftavia.seedveil.SeedVeilPlugin;
import net.craftavia.seedveil.Settings;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerCommandSendEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static net.craftavia.seedveil.Messages.msg;

/** Blocks commands that reveal the seed, hides them from tab completion and reports repeated attempts. */
public final class CommandGuard implements Listener {

    private final SeedVeilPlugin plugin;
    private final Map<UUID, Deque<Long>> attempts = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastAlert = new ConcurrentHashMap<>();

    public CommandGuard(final SeedVeilPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(final PlayerCommandPreprocessEvent event) {
        final Settings settings = plugin.settings();
        if (!settings.commandsEnabled() || settings.blockedCommands().isEmpty()) {
            return;
        }
        final String normalized = Settings.normalizeCommand(event.getMessage());
        final String match = settings.matchBlocked(normalized);
        if (match == null) {
            return;
        }
        final Player player = event.getPlayer();
        if (player.hasPermission(SeedVeilPlugin.PERM_BYPASS)) {
            return;
        }
        event.setCancelled(true);
        Messages.send(player, "command-blocked");
        plugin.getSLF4JLogger().info("{} tried a blocked command: /{}", player.getName(), normalized);
        trackAttempt(player, normalized, settings);
    }

    private void trackAttempt(final Player player, final String command, final Settings settings) {
        final long now = System.currentTimeMillis();
        final long windowMs = settings.probeWindowSeconds() * 1000L;
        final Deque<Long> deque = attempts.computeIfAbsent(player.getUniqueId(), k -> new ArrayDeque<>());
        final int count;
        synchronized (deque) {
            deque.addLast(now);
            while (!deque.isEmpty() && deque.peekFirst() < now - windowMs) {
                deque.pollFirst();
            }
            count = deque.size();
        }
        if (count < settings.probeThreshold()) {
            return;
        }
        final Long last = lastAlert.get(player.getUniqueId());
        if (last != null && last > now - windowMs) {
            return;
        }
        lastAlert.put(player.getUniqueId(), now);
        final Component alert = msg("alert.command-probe", Component.text(player.getName()),
                Component.text(settings.probeWindowSeconds()), Component.text(count), Component.text(command));
        Bukkit.broadcast(alert, SeedVeilPlugin.PERM_NOTIFY);
        plugin.getSLF4JLogger().warn(Messages.plain(alert));
    }

    /** Removes single-word blocked commands (/seed, /minecraft:seed) from the tab completion list. */
    @EventHandler
    public void onCommandSend(final PlayerCommandSendEvent event) {
        final Settings settings = plugin.settings();
        if (!settings.commandsEnabled() || event.getPlayer().hasPermission(SeedVeilPlugin.PERM_BYPASS)) {
            return;
        }
        final Set<String> singles = new HashSet<>();
        for (final String pattern : settings.blockedCommands()) {
            if (!pattern.contains(" ")) {
                singles.add(pattern);
            }
        }
        if (singles.isEmpty()) {
            return;
        }
        event.getCommands().removeIf(command -> {
            String base = command.toLowerCase(Locale.ROOT);
            final int colon = base.indexOf(':');
            if (colon >= 0 && colon < base.length() - 1) {
                base = base.substring(colon + 1);
            }
            return singles.contains(base);
        });
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        attempts.remove(event.getPlayer().getUniqueId());
        lastAlert.remove(event.getPlayer().getUniqueId());
    }
}
