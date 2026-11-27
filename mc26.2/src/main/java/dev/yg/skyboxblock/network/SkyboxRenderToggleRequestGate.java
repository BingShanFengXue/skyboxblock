package dev.yg.skyboxblock.network;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Per-connection replay, compare-and-set, and request-rate guard for the client visibility channel. */
public final class SkyboxRenderToggleRequestGate {
    public static final long MIN_REQUEST_INTERVAL_NANOS = 250_000_000L;
    private static final Map<UUID, State> STATES = new HashMap<>();

    private SkyboxRenderToggleRequestGate() { }

    public enum Decision { ACCEPT, STALE_CONNECTION, DUPLICATE, STALE_REQUEST, RATE_LIMITED, INVALID_REQUEST, STALE_STATE }
    private record State(Object connection, long lastRequestId, long lastRequestNanos) { }

    public static synchronized Decision evaluate(UUID playerId, Object connection, boolean currentConnection,
                                                 long requestId, boolean expectedVisible,
                                                 boolean desiredVisible, boolean actualVisible, long nowNanos) {
        if (!currentConnection || connection == null || playerId == null) return Decision.STALE_CONNECTION;
        State previous = STATES.get(playerId);
        if (previous == null || previous.connection() != connection)
            previous = new State(connection, 0L, Long.MIN_VALUE);
        if (requestId <= 0L) {
            STATES.put(playerId, new State(connection, previous.lastRequestId(), nowNanos));
            return Decision.INVALID_REQUEST;
        }
        if (requestId == previous.lastRequestId()) {
            STATES.put(playerId, new State(connection, previous.lastRequestId(), nowNanos));
            return Decision.DUPLICATE;
        }
        if (requestId < previous.lastRequestId()) {
            STATES.put(playerId, new State(connection, previous.lastRequestId(), nowNanos));
            return Decision.STALE_REQUEST;
        }
        boolean rateLimited = previous.lastRequestNanos() != Long.MIN_VALUE
                && nowNanos - previous.lastRequestNanos() < MIN_REQUEST_INTERVAL_NANOS;
        // Consume every new positive sequence, including rejected compare-and-set/rate-limited requests.
        STATES.put(playerId, new State(connection, requestId, nowNanos));
        if (rateLimited) return Decision.RATE_LIMITED;
        if (expectedVisible == desiredVisible) return Decision.INVALID_REQUEST;
        if (actualVisible != expectedVisible) return Decision.STALE_STATE;
        return Decision.ACCEPT;
    }

    public static synchronized void disconnect(UUID playerId, Object connection) {
        State current = STATES.get(playerId);
        if (current != null && current.connection() == connection) STATES.remove(playerId);
    }

    static synchronized void clearForTests() { STATES.clear(); }
}
