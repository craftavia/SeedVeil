package net.craftavia.seedveil.packet;

import java.net.SocketAddress;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Attaches the handler when a player logs in instead of scanning every connection on every tick.
 * <p>
 * During the pre-login event (Netty/authentication thread) the new connection may not be in the server's connection
 * list yet; the list is updated on the main thread on a later tick. So if the connection is not found during
 * pre-login, its address is retried every tick for {@link #RETRY_TICKS} ticks and the retry stops as soon as the
 * handler is attached. As a safety net a full scan runs every {@link #FULL_SCAN_INTERVAL} ticks (respawn and
 * dimension change packets also need every channel to have the handler).
 */
public final class LoginInjector {

    /** Longest time (ticks) a single connection is retried after pre-login. */
    static final int RETRY_TICKS = 100;
    /** Interval (ticks) of the full scan over all connections. */
    static final int FULL_SCAN_INTERVAL = 20;
    /** Upper bound of pending addresses during a login flood; the rest is left to the full scan. */
    private static final int MAX_PENDING = 256;

    private final HashedSeedHook hook;
    /** Client address -> tick at which retrying stops. */
    private final Map<SocketAddress, Long> pending = new ConcurrentHashMap<>();
    private volatile long tick;

    public LoginInjector(final HashedSeedHook hook) {
        this.hook = hook;
    }

    /** Any thread (pre-login event). If the handler cannot be attached right away, the address is queued for retries. */
    public void request(final SocketAddress address) {
        if (address == null || !hook.active()) {
            return;
        }
        if (hook.injectAddress(address)) {
            return;
        }
        if (pending.size() >= MAX_PENDING) {
            return;
        }
        pending.put(address, tick + RETRY_TICKS);
    }

    /** Main thread, every tick. With no pending logins only the counter moves. */
    public void tick() {
        final long now = ++tick;
        if (!hook.active()) {
            pending.clear();
            return;
        }
        if (!pending.isEmpty()) {
            for (final Iterator<Map.Entry<SocketAddress, Long>> it = pending.entrySet().iterator(); it.hasNext(); ) {
                final Map.Entry<SocketAddress, Long> entry = it.next();
                if (hook.injectAddress(entry.getKey()) || now >= entry.getValue()) {
                    it.remove();
                }
            }
        }
        if (now % FULL_SCAN_INTERVAL == 0) {
            hook.injectAll();
        }
    }

    public int pendingCount() {
        return pending.size();
    }
}
