package dev.yg.skyboxblock.client;

/** Pure acknowledgement regression used by the server-backend test entry point. */
public final class SkyboxRenderToggleRegression {
    private static int checks;
    private SkyboxRenderToggleRegression() { }

    public static int run() {
        checks = 0;
        Object connection = new Object();
        Object nextConnection = new Object();
        check(SkyboxRenderToggle.acceptsResult(7L, connection, 7L, connection),
                "matching request ID on its active connection accepts the response");
        check(!SkyboxRenderToggle.acceptsResult(7L, connection, 8L, connection),
                "an unrelated response cannot finish this request");
        check(!SkyboxRenderToggle.acceptsResult(7L, connection, 7L, nextConnection),
                "an old connection response cannot finish a new connection request");
        check(!SkyboxRenderToggle.acceptsResult(0L, connection, 0L, connection),
                "zero or unset request IDs are never accepted");
        check(!SkyboxRenderToggle.acceptsResult(7L, null, 7L, connection),
                "a response without a bound request connection is ignored");
        System.out.println("SkyboxRenderToggleRegression: PASS " + checks + " checks");
        return checks;
    }

    private static void check(boolean condition, String description) {
        checks++;
        if (!condition) throw new AssertionError(description);
    }
}
