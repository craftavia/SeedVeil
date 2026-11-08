package net.craftavia.seedveil;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.craftavia.seedveil.command.SeedVeilCommand;
import net.craftavia.seedveil.listener.CommandGuard;
import net.craftavia.seedveil.listener.ConnectionListener;
import net.craftavia.seedveil.listener.StructureMonitor;
import net.craftavia.seedveil.packet.HashedSeedHook;
import net.craftavia.seedveil.packet.LoginInjector;
import net.craftavia.seedveil.service.ConfigScrambler;
import net.craftavia.seedveil.service.StatusService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.translation.Translator;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static net.craftavia.seedveil.Messages.msg;

public final class SeedVeilPlugin extends JavaPlugin {

    public static final String PERM_ADMIN = "seedveil.admin";
    public static final String PERM_BYPASS = "seedveil.bypass";
    public static final String PERM_NOTIFY = "seedveil.notify";

    private final AtomicBoolean busy = new AtomicBoolean();
    private Messages messages;
    private volatile Settings settings;
    private HashedSeedHook hook;
    private LoginInjector injector;
    private ConfigScrambler scrambler;
    private StatusService status;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        settings = Settings.from(getConfig());

        messages = new Messages(this);
        messages.saveDefaults();
        try {
            messages.apply(messages.read(), defaultLocale(getConfig()));
        } catch (final IOException | RuntimeException e) {
            // Protection keeps running; players would only see message keys instead of text.
            getSLF4JLogger().error("Could not read the message files", e);
        }

        hook = new HashedSeedHook(getSLF4JLogger());
        if (settings.hashedSeed()) {
            hook.enable();
        }
        injector = new LoginInjector(hook);
        // spigot.yml and config/paper-world-defaults.yml are read from the server directory.
        scrambler = new ConfigScrambler(Path.of("").toAbsolutePath());
        status = new StatusService(this);

        getServer().getPluginManager().registerEvents(new ConnectionListener(hook, injector, getSLF4JLogger()), this);
        getServer().getPluginManager().registerEvents(new CommandGuard(this), this);
        getServer().getPluginManager().registerEvents(new StructureMonitor(this), this);

        // New connections may reach the server's connection list on the main thread, after the pre-login event.
        // A connection not found during pre-login is retried by address for a few ticks; a full scan every
        // 20 ticks is the safety net. With no pending logins the task only advances a counter.
        getServer().getGlobalRegionScheduler().runAtFixedRate(this, task -> {
            try {
                injector.tick();
            } catch (final LinkageError | RuntimeException e) {
                getSLF4JLogger().debug("Hashed seed scan failed: {}", e.toString());
            }
        }, 1L, 1L);

        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                new SeedVeilCommand(this).register(event.registrar()));

        // Print the summary to the console once the worlds and the other plugins are loaded.
        getServer().getGlobalRegionScheduler().runDelayed(this, task -> status.report(getServer().getConsoleSender()), 40L);
    }

    @Override
    public void onDisable() {
        if (hook != null) {
            hook.disable();
        }
        if (messages != null) {
            messages.unregister();
        }
    }

    private static Locale defaultLocale(final ConfigurationSection config) {
        final Locale locale = Translator.parseLocale(config.getString("default-language", "en"));
        return locale == null ? Locale.ENGLISH : locale;
    }

    /** Call on the main thread. */
    public List<Path> worldFolders() {
        final List<Path> folders = new ArrayList<>();
        for (final World world : getServer().getWorlds()) {
            folders.add(world.getWorldFolder().toPath());
        }
        return folders;
    }

    /** Scrambles the spigot.yml structure seeds and turns on Paper's feature seeds; asks to confirm on a used world. */
    public void scramble(final CommandSender sender, final boolean confirmed) {
        if (!busy.compareAndSet(false, true)) {
            Messages.send(sender, "busy");
            return;
        }
        final List<Path> worlds = worldFolders();
        final int maxRegions = settings.maxRegionFilesWithoutConfirm();
        getServer().getAsyncScheduler().runNow(this, task -> {
            Component result;
            try {
                final int regions = scrambler.countRegionFiles(worlds);
                if (regions > maxRegions && !confirmed) {
                    result = msg("scramble.confirm-required", Component.text(regions));
                } else {
                    final ConfigScrambler.ScrambleResult done = scrambler.scramble(worlds);
                    getSLF4JLogger().warn("Structure seeds scrambled ({} keys), Paper feature seeds {}. A restart is required.",
                            done.structureKeysSet(), done.featureSeedsSet() ? "turned on" : "not changed (config/paper-world-defaults.yml not found)");
                    result = msg("scramble.done", Component.text(done.structureKeysSet()));
                }
            } catch (final IOException | RuntimeException e) {
                getSLF4JLogger().error("Could not write the server config files.", e);
                result = msg("scramble.failed");
            } finally {
                busy.set(false);
            }
            final Component message = result;
            getServer().getGlobalRegionScheduler().run(this, t -> sender.sendMessage(message));
        });
    }

    /** config.yml and the messages are read in the background and applied on the main thread. */
    public void reload(final CommandSender sender) {
        if (!busy.compareAndSet(false, true)) {
            Messages.send(sender, "busy");
            return;
        }
        getServer().getAsyncScheduler().runNow(this, task -> {
            try {
                final YamlConfiguration config = new YamlConfiguration();
                final File configFile = new File(getDataFolder(), "config.yml");
                if (configFile.exists()) {
                    config.load(configFile);
                }
                final Map<Locale, Map<String, String>> newMessages = messages.read();
                getServer().getGlobalRegionScheduler().run(this, t -> {
                    try {
                        reloadConfigFrom(config);
                        messages.apply(newMessages, defaultLocale(getConfig()));
                        settings = Settings.from(getConfig());
                        if (settings.hashedSeed() && !hook.active()) {
                            hook.enable();
                        } else if (!settings.hashedSeed() && hook.active()) {
                            hook.disable();
                        }
                        Messages.send(sender, "reload.done");
                    } finally {
                        busy.set(false);
                    }
                });
            } catch (final IOException | InvalidConfigurationException | RuntimeException e) {
                busy.set(false);
                getSLF4JLogger().error("Reload failed.", e);
                getServer().getGlobalRegionScheduler().run(this, t -> Messages.send(sender, "reload.failed"));
            }
        });
    }

    /** Copies a config read in the background into the plugin's config object (the file is not read again). */
    private void reloadConfigFrom(final YamlConfiguration source) {
        final var target = getConfig();
        for (final String key : target.getKeys(false)) {
            target.set(key, null);
        }
        for (final String key : source.getKeys(true)) {
            if (!source.isConfigurationSection(key)) {
                target.set(key, source.get(key));
            }
        }
    }

    public Settings settings() {
        return settings;
    }

    public HashedSeedHook hook() {
        return hook;
    }

    public LoginInjector injector() {
        return injector;
    }

    public ConfigScrambler scrambler() {
        return scrambler;
    }

    public StatusService status() {
        return status;
    }
}
