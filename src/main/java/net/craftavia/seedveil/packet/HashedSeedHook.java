package net.craftavia.seedveil.packet;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.CommonPlayerSpawnInfo;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;

import java.net.SocketAddress;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Replaces the "hashed seed" field of the login and respawn packets with a random value.
 * The client only uses this value to smooth biome borders, so players do not notice anything.
 * The handler is added before "packet_handler" on every channel in the server's connection list.
 */
public final class HashedSeedHook {

    private static final String HANDLER_NAME = "seedveil_hashed_seed";
    private static final String VANILLA_HANDLER = "packet_handler";

    private final Logger log;
    private final SecureRandom random = new SecureRandom();
    private final AtomicLong rewritten = new AtomicLong();
    private volatile boolean active;
    private volatile String failure = "";

    public HashedSeedHook(final Logger log) {
        this.log = log;
    }

    /** Tries to reach the server internals; on failure the layer stays off and the rest of the plugin keeps working. */
    public boolean enable() {
        try {
            active = true;
            failure = "";
            injectAll();
        } catch (final LinkageError | RuntimeException e) {
            active = false;
            failure = e.getClass().getSimpleName() + ": " + e.getMessage();
            log.warn("Could not enable the hashed seed layer (the server version may not be supported): {}", failure);
        }
        return active;
    }

    public void disable() {
        active = false;
        try {
            final List<Connection> connections = MinecraftServer.getServer().getConnection().getConnections();
            synchronized (connections) {
                for (final Connection connection : connections) {
                    final Channel channel = connection.channel;
                    if (channel != null && channel.pipeline().get(HANDLER_NAME) != null) {
                        try {
                            channel.pipeline().remove(HANDLER_NAME);
                        } catch (final NoSuchElementException ignored) {
                            // Another thread may have removed it at the same time.
                        }
                    }
                }
            }
        } catch (final LinkageError | RuntimeException e) {
            log.debug("Could not remove the hashed seed handler: {}", e.toString());
        }
    }

    public boolean active() {
        return active;
    }

    public String failure() {
        return failure;
    }

    public long rewrittenCount() {
        return rewritten.get();
    }

    /** Adds the handler to every open connection that does not have it yet. Any thread. */
    public void injectAll() {
        if (!active) {
            return;
        }
        final List<Connection> connections = MinecraftServer.getServer().getConnection().getConnections();
        synchronized (connections) {
            for (final Connection connection : connections) {
                inject(connection.channel);
            }
        }
    }

    /**
     * Attaches the handler only to the connection(s) with the given client address; true if it was attached by this call.
     * Returns false for a connection that already has it or is not in the list yet (the caller retries).
     * Any thread.
     */
    public boolean injectAddress(final SocketAddress address) {
        if (!active || address == null) {
            return false;
        }
        boolean injected = false;
        final List<Connection> connections = MinecraftServer.getServer().getConnection().getConnections();
        synchronized (connections) {
            for (final Connection connection : connections) {
                final Channel channel = connection.channel;
                if (channel == null) {
                    continue;
                }
                // Behind a proxy connection.getRemoteAddress() is the forwarded player address,
                // channel.remoteAddress() is the socket address.
                if (!address.equals(connection.getRemoteAddress()) && !address.equals(channel.remoteAddress())) {
                    continue;
                }
                injected |= inject(channel);
            }
        }
        return injected;
    }

    /** Whether the handler is attached to this connection (check after join). */
    public boolean isInjected(final Connection connection) {
        final Channel channel = connection.channel;
        return channel != null && channel.pipeline().get(HANDLER_NAME) != null;
    }

    /** True if the handler was attached by this call. */
    private boolean inject(final Channel channel) {
        if (channel == null || !channel.isOpen()) {
            return false;
        }
        final ChannelPipeline pipeline = channel.pipeline();
        if (pipeline.get(HANDLER_NAME) != null || pipeline.get(VANILLA_HANDLER) == null) {
            return false;
        }
        try {
            pipeline.addBefore(VANILLA_HANDLER, HANDLER_NAME, new Handler());
            return true;
        } catch (final IllegalArgumentException | NoSuchElementException ignored) {
            // Another thread added it at the same time, or the channel closed.
            return false;
        }
    }

    /** Returns a copy with a fake seed if the packet carries the seed field, otherwise the same object. */
    public Object rewrite(final Object msg) {
        if (msg instanceof ClientboundLoginPacket || msg instanceof ClientboundRespawnPacket) {
            final Object copy = RecordCopier.replace(msg, CommonPlayerSpawnInfo.class, this::fake);
            if (copy != msg) {
                rewritten.incrementAndGet();
            }
            return copy;
        }
        if (msg instanceof ClientboundBundlePacket bundle) {
            boolean changed = false;
            final List<Packet<? super ClientGamePacketListener>> out = new ArrayList<>();
            for (final Packet<? super ClientGamePacketListener> sub : bundle.subPackets()) {
                final Object replaced = rewrite(sub);
                if (replaced != sub) {
                    changed = true;
                }
                @SuppressWarnings("unchecked")
                final Packet<? super ClientGamePacketListener> typed = (Packet<? super ClientGamePacketListener>) replaced;
                out.add(typed);
            }
            return changed ? new ClientboundBundlePacket(out) : bundle;
        }
        return msg;
    }

    // Fields are copied from the record components: the component list differs between 26.2 and 26.3
    // (previousGameType became an Optional), and a fixed constructor call would throw NoSuchMethodError on the
    // other version and kick the player during login.
    private Object fake(final Object info) {
        return RecordCopier.replace(info, long.class, ignored -> random.nextLong());
    }

    /** Runs on the Netty IO thread; no Bukkit API calls here. */
    private final class Handler extends ChannelOutboundHandlerAdapter {
        @Override
        public void write(final ChannelHandlerContext ctx, final Object msg, final ChannelPromise promise) throws Exception {
            Object out = msg;
            if (active) {
                try {
                    out = rewrite(msg);
                } catch (final LinkageError | RuntimeException e) {
                    // If the packet cannot be rewritten, send the real one and turn the layer off: never kick a player.
                    active = false;
                    failure = e.getClass().getSimpleName() + ": " + e.getMessage();
                    log.warn("Hashed seed layer turned off, a packet could not be rewritten: {}", failure);
                }
            }
            super.write(ctx, out, promise);
        }
    }
}
