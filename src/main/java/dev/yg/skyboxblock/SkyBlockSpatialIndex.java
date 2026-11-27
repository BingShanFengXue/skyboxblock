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

    /** Work counters returned by a streaming range visit; no entity result collection is materialized. */
    public record VisitStats(int occupiedColumnsVisited, int occupiedChunksVisited,
                             int indexedEntriesVisited, int emptyChunkKeyLookups) { }

    /**
     * Compatibility snapshot query. New rendering code should use {@link #visitNear} to avoid
     * materializing and copying the per-view entity result list.
     */
    public synchronized Query<T> query(Object world, double cameraX, double cameraZ, double radiusBlocks) {
        ArrayList<T> found = new ArrayList<>();
        VisitStats stats = visitNear(world, cameraX, cameraZ, radiusBlocks, found::add);
        return new Query<>(found, stats.occupiedColumnsVisited(), stats.occupiedChunksVisited(),
                stats.indexedEntriesVisited(), stats.emptyChunkKeyLookups());
    }

    /**
     * Visits live values in the same X-column, Z-chunk, then bucket-insertion order as {@link #query}.
     * Weakly-referenced dead entries are removed as encountered; empty chunk and column buckets are
     * removed through their range iterators. The callback runs while this index is synchronized and
     * must not mutate this same index.
     */
    public synchronized VisitStats visitNear(Object world, double cameraX, double cameraZ, double radiusBlocks,
                                              java.util.function.Consumer<? super T> visitor) {
        java.util.Objects.requireNonNull(visitor, "visitor");
        if (world == null) return new VisitStats(0, 0, 0, 0);
        if (!Double.isFinite(cameraX) || !Double.isFinite(cameraZ)
                || !Double.isFinite(radiusBlocks) || radiusBlocks < 0D) {
            throw new IllegalArgumentException("query center/radius must be finite and radius non-negative");
        }
        NavigableMap<Integer, NavigableMap<Integer, ArrayList<Handle<T>>>> columns = worlds.get(world);
        if (columns == null || columns.isEmpty()) return new VisitStats(0, 0, 0, 0);

        int minX = chunkFloor(cameraX - radiusBlocks), maxX = chunkFloor(cameraX + radiusBlocks);
        int minZ = chunkFloor(cameraZ - radiusBlocks), maxZ = chunkFloor(cameraZ + radiusBlocks);
        NavigableMap<Integer, NavigableMap<Integer, ArrayList<Handle<T>>>> xRange =
                columns.subMap(minX, true, maxX, true);
        int columnsVisited = 0, chunksVisited = 0, entriesVisited = 0;
        Iterator<Map.Entry<Integer, NavigableMap<Integer, ArrayList<Handle<T>>>>> columnIterator =
                xRange.entrySet().iterator();
        while (columnIterator.hasNext()) {
            NavigableMap<Integer, ArrayList<Handle<T>>> column = columnIterator.next().getValue();
            columnsVisited++;
            NavigableMap<Integer, ArrayList<Handle<T>>> zRange = column.subMap(minZ, true, maxZ, true);
            Iterator<Map.Entry<Integer, ArrayList<Handle<T>>>> chunkIterator = zRange.entrySet().iterator();
            while (chunkIterator.hasNext()) {
                ArrayList<Handle<T>> bucket = chunkIterator.next().getValue();
                chunksVisited++;
                Iterator<Handle<T>> iterator = bucket.iterator();
                while (iterator.hasNext()) {
                    Handle<T> handle = iterator.next();
                    entriesVisited++;
                    T value = handle.value.get();
                    if (value == null || handle.world.get() != world) iterator.remove();
                    else visitor.accept(value);
                }
                if (bucket.isEmpty()) chunkIterator.remove();
            }
            if (column.isEmpty()) columnIterator.remove();
        }
        if (columns.isEmpty()) worlds.remove(world);
        return new VisitStats(columnsVisited, chunksVisited, entriesVisited, 0);
    }

    private static int chunkFloor(double blockCoordinate) {
        double chunk = Math.floor(blockCoordinate / 16D);
        if (chunk <= Integer.MIN_VALUE) return Integer.MIN_VALUE;
        if (chunk >= Integer.MAX_VALUE) return Integer.MAX_VALUE;
        return (int) chunk;
    }
}
