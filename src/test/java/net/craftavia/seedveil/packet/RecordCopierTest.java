package net.craftavia.seedveil.packet;

import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.CommonPlayerSpawnInfo;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class RecordCopierTest {

    private record Info(String dimension, long seed, Optional<String> previous, boolean flat) {
    }

    private record TwoLongs(long a, long b) {
    }

    @Test
    void replacesTheOnlyComponentOfThatType() {
        final Info original = new Info("overworld", 42L, Optional.empty(), true);
        final Object copy = RecordCopier.replace(original, long.class, ignored -> 7L);
        assertEquals(new Info("overworld", 7L, Optional.empty(), true), copy);
    }

    @Test
    void leavesAmbiguousOrMissingTypesAlone() {
        final TwoLongs twoLongs = new TwoLongs(1L, 2L);
        assertSame(twoLongs, RecordCopier.replace(twoLongs, long.class, ignored -> 0L));
        final Info info = new Info("x", 1L, Optional.empty(), false);
        assertSame(info, RecordCopier.replace(info, int.class, ignored -> 0));
        final String notARecord = "seed";
        assertSame(notARecord, RecordCopier.replace(notARecord, long.class, ignored -> 0L));
    }

    /** The assumptions the hashed seed layer relies on, checked against the server classes of the dev bundle. */
    @Test
    void serverPacketsHaveTheExpectedShape() {
        assertEquals(1, count(CommonPlayerSpawnInfo.class, long.class), "CommonPlayerSpawnInfo long components");
        assertEquals(1, count(ClientboundLoginPacket.class, CommonPlayerSpawnInfo.class), "login packet spawn info");
        assertEquals(1, count(ClientboundRespawnPacket.class, CommonPlayerSpawnInfo.class), "respawn packet spawn info");
    }

    private static long count(final Class<?> record, final Class<?> type) {
        return Arrays.stream(record.getRecordComponents()).filter(c -> c.getType() == type).count();
    }
}
