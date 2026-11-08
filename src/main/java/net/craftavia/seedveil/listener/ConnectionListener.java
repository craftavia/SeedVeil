package net.craftavia.seedveil.listener;

import net.craftavia.seedveil.packet.HashedSeedHook;
import net.craftavia.seedveil.packet.LoginInjector;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.slf4j.Logger;

/**
 * Attaches the handler while the connection is still logging in (long before the first play packet)
 * and checks after join that it really is in place.
 */
public final class ConnectionListener implements Listener {

    private final HashedSeedHook hook;
    private final LoginInjector injector;
    private final Logger log;

    public ConnectionListener(final HashedSeedHook hook, final LoginInjector injector, final Logger log) {
        this.hook = hook;
        this.injector = injector;
        this.log = log;
    }

    /**
     * Targets only this connection: if it cannot be attached right away (the list is not updated yet),
     * LoginInjector retries the same address on the main thread for a few ticks. Without an address: full scan.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPreLogin(final AsyncPlayerPreLoginEvent event) {
        if (!hook.active()) {
            return;
        }
        try {
            injector.request(event.getConnection().getClientAddress());
        } catch (final LinkageError | RuntimeException e) {
            log.debug("Could not get the login address, running a full scan: {}", e.toString());
            hook.injectAll();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(final PlayerJoinEvent event) {
        if (!hook.active()) {
            return;
        }
        try {
            final var connection = ((CraftPlayer) event.getPlayer()).getHandle().connection.connection;
            if (!hook.isInjected(connection)) {
                log.warn("Hashed seed handler was not attached for {}; this player may have received the real hashed seed.",
                        event.getPlayer().getName());
                // Even if the login packet was missed, protect the following respawn/dimension change packets.
                hook.injectAll();
            }
        } catch (final LinkageError | RuntimeException e) {
            log.debug("Could not verify the hashed seed handler: {}", e.toString());
        }
    }
}
