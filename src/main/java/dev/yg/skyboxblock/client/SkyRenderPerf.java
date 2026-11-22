package dev.yg.skyboxblock.client;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL33;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;

/** Opt-in fixture telemetry. Timestamp results are read only after the driver marks them ready. */
public final class SkyRenderPerf {
    static final boolean ENABLED = Boolean.getBoolean("skyboxblock.perf.capture");
    enum Stage { OPAQUE, WORLD_CANVAS, DISPLAY_CANVAS, RESOLVE, TRANS_BEFORE, TRANS_AFTER }
    private static final Stage[] STAGES = Stage.values();
    private static final int QUERY_PAIRS = 48;
    private static Capture active;

    private SkyRenderPerf() { }

    public static void arm(int frames) {
        if (!ENABLED || active != null || frames < 60 || frames > 3000)
            throw new IllegalStateException("Invalid or overlapping skybox performance capture");
        active = new Capture(frames);
    }

    public static boolean done() { return active != null && active.row == active.frames && active.tail >= 12; }

    static void beginFrame() {
        Capture c = active;
        if (!ENABLED || c == null) return;
        c.drain();
        long now = System.nanoTime();
        if (c.row < c.frames) {
            c.interval[c.row] = c.previous == 0 ? -1 : now - c.previous;
            c.inFrame = true;
        } else c.tail++;
        c.previous = now;
    }

    static void endFrame(boolean complete) {
        Capture c = active;
        if (!ENABLED || c == null || !c.inFrame) return;
        c.inFrame = false;
        if (complete) c.row++;
        else c.aborted = true;
    }

    static void view(boolean root, boolean enabled, int faces) {
        Capture c = active;
        if (!ENABLED || c == null || !c.inFrame || !root) return;
        c.faces[c.row] = faces;
        c.visible[c.row] = enabled ? 1 : 0;
    }

    static void copies(boolean root, long pixels) {
        Capture c = active;
        if (!ENABLED || c == null || !c.inFrame || !root) return;
        c.copyPixels[c.row] += pixels;
    }

    static Span span(Stage stage, boolean root) {
        Capture c = active;
        if (!ENABLED || c == null || !c.inFrame || !root) return Span.NONE;
        Pair pair = c.free.pollFirst();
        if (pair != null) GL33.glQueryCounter(pair.start, GL33.GL_TIMESTAMP);
        return new Span(c, c.row, stage.ordinal(), pair, System.nanoTime());
    }

    static final class Span implements AutoCloseable {
        private static final Span NONE = new Span(null, 0, 0, null, 0);
        private final Capture capture;
        private final int row, stage;
        private final Pair pair;
        private final long start;

        Span(Capture capture, int row, int stage, Pair pair, long start) {
            this.capture = capture; this.row = row; this.stage = stage; this.pair = pair; this.start = start;
        }

        @Override public void close() {
            if (capture == null) return;
            capture.cpu[row][stage] += System.nanoTime() - start;
            if (pair != null) {
                GL33.glQueryCounter(pair.end, GL33.GL_TIMESTAMP);
                pair.row = row; pair.stage = stage;
                capture.pending.addLast(pair);
            }
        }
    }

    private static final class Pair {
        final int start = GL15.glGenQueries(), end = GL15.glGenQueries();
        int row, stage;
        void delete() { GL15.glDeleteQueries(start); GL15.glDeleteQueries(end); }
    }

    private static final class Capture {
        final int frames;
        final long[] interval, copyPixels;
        final int[] faces, visible;
        final long[][] cpu, gpu;
        final ArrayDeque<Pair> free = new ArrayDeque<>(), pending = new ArrayDeque<>();
        int row, tail;
        long previous;
        boolean inFrame, aborted;
        Capture(int frames) {
            this.frames = frames;
            interval = new long[frames]; copyPixels = new long[frames];
            faces = new int[frames]; visible = new int[frames];
            java.util.Arrays.fill(faces, -1);
            java.util.Arrays.fill(visible, -1);
            cpu = new long[frames][STAGES.length]; gpu = new long[frames][STAGES.length];
            if (GL.getCapabilities().OpenGL33 || GL.getCapabilities().GL_ARB_timer_query)
                for (int i = 0; i < QUERY_PAIRS; i++) free.addLast(new Pair());
        }
        void drain() {
            while (!pending.isEmpty()) {
                Pair p = pending.peekFirst();
                if (GL15.glGetQueryObjecti(p.end, GL15.GL_QUERY_RESULT_AVAILABLE) == 0) break;
                pending.removeFirst();
                gpu[p.row][p.stage] += GL33.glGetQueryObjectui64(p.end, GL15.GL_QUERY_RESULT)
                        - GL33.glGetQueryObjectui64(p.start, GL15.GL_QUERY_RESULT);
                free.addLast(p);
            }
        }
    }

    public static void save(Path path) throws IOException {
        Capture c = active;
        if (!done() || c.aborted) throw new IllegalStateException("Skybox performance capture did not complete");
        active = null;
        c.drain();
        for (Pair p : c.pending) p.delete();
        for (Pair p : c.free) p.delete();
        StringBuilder text = new StringBuilder("frame,frame_interval_ns,faces,render_enabled,copy_pixels");
        for (Stage stage : STAGES) text.append(',').append(stage).append("_CPU_ns,").append(stage).append("_GPU_ns");
        text.append('\n');
        for (int row = 0; row < c.frames; row++) {
            text.append(row).append(',').append(c.interval[row]).append(',').append(c.faces[row])
                    .append(',').append(c.visible[row]).append(',').append(c.copyPixels[row]);
            for (int i = 0; i < STAGES.length; i++) {
                text.append(',').append(c.cpu[row][i]).append(',');
                if (c.gpu[row][i] != 0) text.append(c.gpu[row][i]);
            }
            text.append('\n');
        }
        Files.createDirectories(path.toAbsolutePath().getParent());
        Files.writeString(path, text, StandardCharsets.UTF_8);
    }
}
