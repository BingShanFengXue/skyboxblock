package dev.yg.skyboxblock.network;

import java.util.List;

/** Pure manifest protocol regression, invoked by the existing server-backend suite. */
public final class SkyboxRegistryManifestRegression {
    private static int checks;
    private SkyboxRegistryManifestRegression() { }

    public static int run() {
        checks = 0;
        var encoded = SkyboxRegistryManifestPayload.encode(List.of("skyboxblock:z_sky", "skyboxblock:a_sky"));
        List<String> decoded;
        try { decoded = SkyboxRegistryManifestPayload.decode(encoded); }
        finally { encoded.release(); }
        check(decoded.equals(List.of("skyboxblock:a_sky", "skyboxblock:z_sky")),
                "manifest wire form is bounded and canonicalized by registered ID only");
        check(SkyboxRegistryManifestPayload.mismatchReport(decoded,
                        List.of("skyboxblock:z_sky", "skyboxblock:a_sky")) == null,
                "same ID set with a different order matches");
        String report = SkyboxRegistryManifestPayload.mismatchReport(
                List.of("skyboxblock:server_only", "skyboxblock:shared"),
                List.of("skyboxblock:client_only", "skyboxblock:shared"));
        check(report != null && report.contains("skyboxblock:server_only")
                        && report.contains("skyboxblock:client_only") && !report.contains("picture"),
                "mismatch report exposes only missing/extra registration IDs");
        check(SkyboxRegistryManifestPayload.mismatchReport(List.of(), List.of()) == null,
                "both empty manifests preserve the ordinary connection path");
        check(SkyboxRegistryManifestPayload.unsupportedClientReport(List.of()) == null,
                "older/non-mod client is allowed when server has no custom IDs");
        check(SkyboxRegistryManifestPayload.unsupportedClientReport(List.of("skyboxblock:custom"))
                        .contains("skyboxblock:custom"),
                "older client gets a readable pre-registry explanation when custom IDs are required");
        throwsType(IllegalArgumentException.class,
                () -> SkyboxRegistryManifestPayload.encode(List.of("skyboxblock:duplicate", "skyboxblock:duplicate")),
                "duplicate IDs are rejected before sending");
        throwsType(IllegalArgumentException.class,
                () -> SkyboxRegistryManifestPayload.encode(java.util.Collections.nCopies(65, "skyboxblock:too_many")),
                "oversized manifests are rejected");
        System.out.println("SkyboxRegistryManifestRegression: PASS " + checks + " checks");
        return checks;
    }

    private static void check(boolean value, String description) {
        checks++;
        if (!value) throw new AssertionError(description);
    }

    private static void throwsType(Class<? extends Throwable> expected, Runnable operation, String description) {
        checks++;
        try { operation.run(); }
        catch (Throwable error) {
            if (expected.isInstance(error)) return;
            throw new AssertionError(description + ": wrong exception " + error, error);
        }
        throw new AssertionError(description + ": exception not thrown");
    }
}
