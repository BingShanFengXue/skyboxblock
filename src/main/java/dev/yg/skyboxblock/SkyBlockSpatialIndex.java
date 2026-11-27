package dev.yg.skyboxblock;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.WeakHashMap;

/**
 * Weak, per-world 2D chunk index for loaded sky windows. Range queries enumerate only occupied
 * X columns and occupied chunk buckets in the requested rectangle; they never probe every empty
 * chunk cell in a large configured render distance.
 *
 * <p>The index holds weak world keys and weak entity references. A value therefore cannot keep its
 * WeakHashMap world key alive through the normal entity -> level reference chain.
 */
public final class SkyBlockSpatialIndex<T> {
    private final WeakHashMap<Object, NavigableMap<Integer, NavigableMap<Integer, ArrayList<Handle<T>>>>> worlds =
            new WeakHashMap<>();

    public static final class Handle<T> {
        private final WeakReference<Object> world;
        private final WeakReference<T> value;
        private final int chunkX;
        private final int chunkZ;

        private Handle(Object world, int chunkX, int chunkZ, T value) {
            this.world = new WeakReference<>(world);
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.value = new WeakReference<>(value);
        }
    }

    /** Deterministic work counters; emptyChunkKeyLookups is zero by construction. */
    public record Query<T>(List<T> entities, int occupiedColumnsVisited, int occupiedChunksVisited,
                           int indexedEntriesVisited, int emptyChunkKeyLookups) {
        public Query {
            entities = List.copyOf(entities);
        }
    }

    public synchronized Handle<T> add(Object world, int blockX, int blockZ, T value) {
        if (world == null || value == null) throw new NullPointerException("world/value");
        int chunkX = blockX >> 4;
        int chunkZ = blockZ >> 4;
        Handle<T> handle = new Handle<>(world, chunkX, chunkZ, value);
        worlds.computeIfAbsent(world, ignored -> new TreeMap<>())
                .computeIfAbsent(chunkX, ignored -> new TreeMap<>())
                .computeIfAbsent(chunkZ, ignored -> new ArrayList<>()).add(handle);
        return handle;
    }

    public synchronized void remove(Handle<T> handle) {
        if (handle == null) return;
        Object world = handle.world.get();
        if (world == null) return;
        NavigableMap<Integer, NavigableMap<Integer, ArrayList<Handle<T>>>> columns = worlds.get(world);
        if (columns == null) return;
        NavigableMap<Integer, ArrayList<Handle<T>>> chunks = columns.get(handle.chunkX);
        if (chunks == null) return;
        ArrayList<Handle<T>> bucket = chunks.get(handle.chunkZ);
        if (bucket == null) return;
        bucket.remove(handle);
        if (bucket.isEmpty()) chunks.remove(handle.chunkZ);
        if (chunks.isEmpty()) columns.remove(handle.chunkX);
        if (columns.isEmpty()) worlds.remove(world);
    }

    /**
     * Enumerates occupied chunk buckets whose X/Z extent intersects the square around the camera.
     * Exact spherical distance and the current render view's ownership checks remain at the caller.
     */
    public synchronized Query<T> query(Object world, double cameraX, double cameraZ, double radiusBlocks) {
        if (world == null) return new Query<>(List.of(), 0, 0, 0, 0);
        if (!Double.isFinite(cameraX) || !Double.isFinite(cameraZ)
                || !Double.isFinite(radiusBlocks) || radiusBlocks < 0D) {
            throw new IllegalArgumentException("query center/radius must be finite and radius non-negative");
        }
        NavigableMap<Integer, NavigableMap<Integer, ArrayList<Handle<T>>>> columns = worlds.get(world);
        if (columns == null || columns.isEmpty()) return new Query<>(List.of(), 0, 0, 0, 0);

        int minX = chunkFloor(cameraX - radiusBlocks), maxX = chunkFloor(cameraX + radiusBlocks);
        int minZ = chunkFloor(cameraZ - radiusBlocks), maxZ = chunkFloor(cameraZ + radiusBlocks);
        NavigableMap<Integer, NavigableMap<Integer, ArrayList<Handle<T>>>> xRange =
                columns.subMap(minX, true, maxX, true);
        ArrayList<T> found = new ArrayList<>();
        ArrayList<int[]> emptyBuckets = new ArrayList<>();
        int columnsVisited = 0, chunksVisited = 0, entriesVisited = 0;
        for (Map.Entry<Integer, NavigableMap<Integer, ArrayList<Handle<T>>>> column : xRange.entrySet()) {
            columnsVisited++;
            NavigableMap<Integer, ArrayList<Handle<T>>> zRange =
                    column.getValue().subMap(minZ, true, maxZ, true);
            for (Map.Entry<Integer, ArrayList<Handle<T>>> chunk : zRange.entrySet()) {
                chunksVisited++;
                ArrayList<Handle<T>> bucket = chunk.getValue();
                Iterator<Handle<T>> iterator = bucket.iterator();
                while (iterator.hasNext()) {
                    Handle<T> handle = iterator.next();
                    entriesVisited++;
                    T value = handle.value.get();
                    if (value == null || handle.world.get() != world) iterator.remove();
                    else found.add(value);
                }
                if (bucket.isEmpty()) emptyBuckets.add(new int[]{column.getKey(), chunk.getKey()});
            }
        }
        // Defer structural removals until range iterators have completed.
        for (int[] empty : emptyBuckets) {
            NavigableMap<Integer, ArrayList<Handle<T>>> chunks = columns.get(empty[0]);
            if (chunks != null) {
                chunks.remove(empty[1]);
                if (chunks.isEmpty()) columns.remove(empty[0]);
            }
        }
        if (columns.isEmpty()) worlds.remove(world);
        return new Query<>(found, columnsVisited, chunksVisited, entriesVisited, 0);
    }

    private static int chunkFloor(double blockCoordinate) {
        double chunk = Math.floor(blockCoordinate / 16D);
        if (chunk <= Integer.MIN_VALUE) return Integer.MIN_VALUE;
        if (chunk >= Integer.MAX_VALUE) return Integer.MAX_VALUE;
        return (int) chunk;
    }
}
