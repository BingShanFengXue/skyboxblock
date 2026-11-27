package dev.yg.skyboxblock.client;

import dev.yg.skyboxblock.SkyBlockSpatialIndex;
import org.joml.Matrix4f;

/** Deterministic range-index work-count and conservative clip-space culling regression. */
public final class SkyBlockSpatialIndexRegression {
    private static int checks;
    private SkyBlockSpatialIndexRegression() { }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) { run(); }

    public static int run() {
        checks = 0;
        rangeQueryAndOwnership();
        maxDistanceBound();
        sidePlaneOracle();
        System.out.println("SkyBlockSpatialIndexRegression: PASS " + checks
                + " checks (occupied-range work counts, level/view isolation, fail-open XY cull)");
        return checks;
    }

    private static void rangeQueryAndOwnership() {
        SkyBlockSpatialIndex<String> index = new SkyBlockSpatialIndex<>();
        Object overworld = new Object(), nether = new Object();
        var centerHandle = index.add(overworld, 0, 0, "center");
        index.add(overworld, 16, 0, "near-east");
        index.add(overworld, 160, 160, "portal-camera");
        index.add(nether, 0, 0, "other-level");
        java.util.ArrayList<String> keepAlive = new java.util.ArrayList<>(10_000);
        for (int i = 0; i < 10_000; i++) {
            int block = 100_000 + i * 16;
            String entity = "remote-" + i;
            keepAlive.add(entity);
            index.add(overworld, block, block, entity);
        }
        var root = index.query(overworld, 0, 0, 64);
        check(root.entities().contains("center") && root.entities().contains("near-east"),
                "root camera receives nearby occupied chunks");
        check(!root.entities().contains("portal-camera") && !root.entities().contains("other-level"),
                "camera range and level identity isolate candidates");
        check(root.entities().size() == 2 && root.indexedEntriesVisited() == 2,
                "10000 distant windows do not enter the local candidate walk");
        check(root.emptyChunkKeyLookups() == 0,
                "range views enumerate occupied keys without probing empty chunk cells");
        check(root.occupiedColumnsVisited() <= 2 && root.occupiedChunksVisited() <= 2,
                "small camera range visits only its occupied columns and chunks");

        var portal = index.query(overworld, 160, 160, 32);
        check(portal.entities().equals(java.util.List.of("portal-camera")),
                "a portal camera performs a fresh range query instead of reusing root candidates");
        index.remove(centerHandle);
        check(!index.query(overworld, 0, 0, 8).entities().contains("center"),
                "chunk unload/removal handle removes stale indexed entities");
        index.add(overworld, 0, 0, "center-reloaded");
        check(index.query(overworld, 0, 0, 8).entities().contains("center-reloaded"),
                "chunk reload/register restores the entity candidate");
    }

    private static void maxDistanceBound() {
        SkyBlockSpatialIndex<Integer> index = new SkyBlockSpatialIndex<>();
        Object world = new Object();
        java.util.ArrayList<Integer> keepAlive = new java.util.ArrayList<>(513);
        for (int chunkX = -256; chunkX <= 256; chunkX++) {
            keepAlive.add(chunkX);
            index.add(world, chunkX * 16, 0, chunkX);
        }
        var query = index.query(world, 0, 0, 4096);
        check(query.entities().size() == 513, "4096-block square contains exactly the 513 occupied X columns");
        check(query.occupiedColumnsVisited() == 513 && query.occupiedChunksVisited() == 513,
                "maximum-distance range work is bounded by occupied columns/chunks, not 513x513 grid cells");
        check(query.indexedEntriesVisited() == 513 && query.emptyChunkKeyLookups() == 0,
                "maximum-distance query visits only present entities and performs no empty-cell lookup");
    }

    private static void sidePlaneOracle() {
        Matrix4f identity = new Matrix4f();
        check(!SkyWindowCull.outsideSidePlanes(0, 0, 0, 0, 0, 0, identity),
                "camera-intersecting cube remains visible");
        check(SkyWindowCull.outsideSidePlanes(2, 0, 0, 0, 0, 0, identity),
                "all cube corners beyond the right plane are culled");
        check(SkyWindowCull.outsideSidePlanes(-3, 0, 0, 0, 0, 0, identity),
                "all cube corners beyond the left plane are culled");
        check(SkyWindowCull.outsideSidePlanes(0, 2, 0, 0, 0, 0, identity),
                "all cube corners beyond the top plane are culled");
        check(SkyWindowCull.outsideSidePlanes(0, -3, 0, 0, 0, 0, identity),
                "all cube corners beyond the bottom plane are culled");
        check(!SkyWindowCull.outsideSidePlanes(1, 0, 0, 0, 0, 0, identity),
                "cube touching the right clip boundary remains visible");
        check(SkyWindowCull.visible(false, 2, 0, 0, 0, 0, 0, identity),
                "portal/mirror fallback never applies the optional side-plane rejection");

        Matrix4f invalid = new Matrix4f().m00(Float.NaN);
        check(!SkyWindowCull.outsideSidePlanes(2, 0, 0, 0, 0, 0, invalid),
                "non-finite matrices fail open");
        Matrix4f zeroW = new Matrix4f().m33(0F);
        check(!SkyWindowCull.outsideSidePlanes(2, 0, 0, 0, 0, 0, zeroW),
                "near-zero homogeneous w fails open");
        Matrix4f perspective = new Matrix4f().perspective((float)Math.toRadians(70), 1, .05F, 512);
        check(!SkyWindowCull.outsideSidePlanes(0, 0, 1, 0, 0, 0, perspective),
                "behind-camera/near-plane uncertainty fails open rather than hiding sky");
    }
}
