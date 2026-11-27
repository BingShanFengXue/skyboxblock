package dev.yg.skyboxblock.network;

import java.util.UUID;

/** Pure per-connection request gate regression, invoked by the existing backend suite. */
public final class SkyboxRenderToggleRequestGateRegression {
    private static int checks;
    private SkyboxRenderToggleRequestGateRegression() { }

    public static int run() {
        checks = 0;
        SkyboxRenderToggleRequestGate.clearForTests();
        UUID player = UUID.fromString("00000000-0000-0000-0000-000000000001");
        Object connectionA = new Object(), connectionB = new Object();
        long t = 1_000_000_000L;
        check(decide(player, connectionA, true, 1, true, false, true, t)
                        == SkyboxRenderToggleRequestGate.Decision.ACCEPT,
                "first current-connection visibility CAS is accepted");
        check(decide(player, connectionA, true, 1, true, false, true, t + 300_000_000L)
                        == SkyboxRenderToggleRequestGate.Decision.DUPLICATE,
                "duplicate request ID never reapplies the setting");
        check(decide(player, connectionA, true, 0, true, false, true, t + 600_000_000L)
                        == SkyboxRenderToggleRequestGate.Decision.INVALID_REQUEST,
                "non-positive sequence is rejected");
        check(decide(player, connectionA, true, 2, false, true, false, t + 900_000_000L)
                == SkyboxRenderToggleRequestGate.Decision.ACCEPT,
                "next monotonic request can toggle back after the rate window");
        check(decide(player, connectionA, true, 1, false, true, false, t + 1_200_000_000L)
                == SkyboxRenderToggleRequestGate.Decision.STALE_REQUEST,
                "old request IDs cannot replay over a newer state");
        check(decide(player, connectionA, true, 3, false, true, false, t + 1_250_000_000L)
                == SkyboxRenderToggleRequestGate.Decision.RATE_LIMITED,
                "per-connection rate limit rejects bursts");
        check(decide(player, connectionA, true, 4, true, false, false, t + 1_550_000_000L)
                == SkyboxRenderToggleRequestGate.Decision.STALE_STATE,
                "compare-and-set rejects a request based on an outdated snapshot");
        check(decide(player, connectionA, false, 5, false, true, false, t + 1_900_000_000L)
                == SkyboxRenderToggleRequestGate.Decision.STALE_CONNECTION,
                "queued request from a disconnected player object is ignored");
        check(decide(player, connectionB, true, 1, false, true, false, t + 2_000_000_000L)
                        == SkyboxRenderToggleRequestGate.Decision.ACCEPT,
                "a new authenticated connection starts a fresh sequence");
        SkyboxRenderToggleRequestGate.disconnect(player, connectionA);
        check(decide(player, connectionB, true, 2, true, false, true, t + 2_300_000_000L)
                        == SkyboxRenderToggleRequestGate.Decision.ACCEPT,
                "disconnect cleanup for an old connection cannot erase the new connection state");
        SkyboxRenderToggleRequestGate.clearForTests();
        System.out.println("SkyboxRenderToggleRequestGateRegression: PASS " + checks + " checks");
        return checks;
    }

    private static SkyboxRenderToggleRequestGate.Decision decide(UUID player, Object connection, boolean current,
                                                                 long id, boolean expected, boolean desired,
                                                                 boolean actual, long nanos) {
        return SkyboxRenderToggleRequestGate.evaluate(player, connection, current, id, expected, desired, actual, nanos);
    }

    private static void check(boolean value, String description) {
        checks++;
        if (!value) throw new AssertionError(description);
    }
}
