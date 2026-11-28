package dev.yg.skyboxblock.client;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Immutable F3-only SkyboxBlock diagnostics and bounded render-frame interval samples. */
final class SkyboxF3Diagnostics {
    private static final FrameIntervals INTERVALS = new FrameIntervals(120);
    private static WeakReference<Object> sampledLevel = new WeakReference<>(null);
    private static boolean sampling;
    private static volatile Publication lastRoot;

    private SkyboxF3Diagnostics() { }

    record Snapshot(boolean root, boolean portal, boolean shadow, int slot, long transaction,
                    boolean enabled, String source, int renderDistance, String distanceSource,
                    List<String> styles, int styleCount,
                    int beEntities, int fallbackEntities, int emittedFaces, int culledFaces,
                    int indexColumns, int indexBuckets, int indexEntries,
                    boolean iris, boolean sodium, boolean distantHorizons, boolean immersivePortals,
                    String itrpStatus, String itrpReason, String drawStatus, String drawReason,
                    int width, int height, float scaleX, float scaleY, float jitterX, float jitterY,
                    int geometryDrawCalls, long geometryUploadBytes, Percentiles percentiles) {
        Snapshot { styles = List.copyOf(styles); }
    }

    private record Publication(WeakReference<Object> level, Snapshot view, int portalSlot, int shadowSlot) { }

    /** Render-thread only. No timing/percentile work until the actual F3 overlay is visible. */
    static boolean frameStart(Object level, boolean overlayVisible) {
        boolean enabled = overlayVisible && level != null;
        if (!enabled) {
            if (sampling) {
                sampling = false;
                INTERVALS.reset();
                lastRoot = null;
            }
            return false;
        }
        if (!sampling || sampledLevel.get() != level) {
            sampledLevel = new WeakReference<>(level);
            INTERVALS.reset();
            lastRoot = null;
        }
        sampling = true;
        INTERVALS.sample(System.nanoTime());
        return true;
    }

    static Percentiles percentiles() { return INTERVALS.percentiles(); }

    static void publish(Snapshot snapshot, int portalSlot, int shadowSlot) {
        if (sampling && snapshot != null && snapshot.root() && !snapshot.portal() && !snapshot.shadow())
            lastRoot = new Publication(sampledLevel, snapshot, portalSlot, shadowSlot);
    }

    static List<String> lines(Object level) {
        Publication published = lastRoot;
        if (published == null || published.level().get() != level || level == null)
            return List.of(PREFIX + localized("skyboxblock.debug.f3.awaiting"));
        return format(published.view(), published.portalSlot(), published.shadowSlot(),
                key -> net.minecraft.locale.Language.getInstance().getOrDefault(key));
    }

    private static final String PREFIX = "[skyboxblock] ";

    static List<String> format(Snapshot s, int portalSlot, int shadowSlot,
                               java.util.function.Function<String, String> translate) {
        var out = new ArrayList<String>(8);
        out.add(PREFIX + tr(translate, "skyboxblock.debug.f3.settings",
                tr(translate, s.enabled() ? "skyboxblock.debug.f3.enabled" : "skyboxblock.debug.f3.disabled"),
                tr(translate, s.source().equals("SERVER") ? "skyboxblock.debug.f3.source_server" : "skyboxblock.debug.f3.source_local"),
                s.renderDistance(), distanceSource(translate, s.distanceSource())));
        var styles = new ArrayList<String>(3);
        String separator = tr(translate, "skyboxblock.debug.f3.styles_separator");
        for(String raw:s.styles()) {
            String id=raw.startsWith("skyboxblock:")?raw.substring("skyboxblock:".length()):raw;
            if(styles.contains(id)) continue;
            if(styles.size()==3) break;
            styles.add(shortText(id,17));
        }
        String styleSummary=styles.isEmpty()?"-":String.join(separator,styles);
        if(s.styleCount()>styles.size() && !styles.isEmpty())
            styleSummary+=tr(translate,"skyboxblock.debug.f3.more_styles",s.styleCount()-styles.size());
        out.add(PREFIX + tr(translate, "skyboxblock.debug.f3.styles", styleSummary));
        out.add(PREFIX + tr(translate, "skyboxblock.debug.f3.entities_faces",
                s.beEntities(), s.fallbackEntities(), s.emittedFaces(), s.culledFaces()));
        StringBuilder slots = new StringBuilder();
        if (portalSlot >= 0) slots.append(tr(translate, "skyboxblock.debug.f3.view_portal_slot", portalSlot));
        if (shadowSlot >= 0) slots.append(tr(translate, "skyboxblock.debug.f3.view_shadow_slot", shadowSlot));
        out.add(PREFIX + tr(translate, "skyboxblock.debug.f3.index",
                s.indexColumns(), s.indexBuckets(), s.indexEntries(), slots));
        String viewKey = s.shadow() ? "skyboxblock.debug.f3.view_shadow"
                : s.portal() ? "skyboxblock.debug.f3.view_portal"
                : s.root() ? "skyboxblock.debug.f3.view_root" : "skyboxblock.debug.f3.view_child";
        String phaseKey = "skyboxblock.debug.f3.phase_" + phaseName(s.drawStatus());
        String extent = s.width() > 0 && s.height() > 0 ? s.width() + "x" + s.height() : "-";
        String scaleX = String.format(Locale.ROOT, "%.2f", s.scaleX());
        String scaleY = String.format(Locale.ROOT, "%.2f", s.scaleY());
        String jitterX = String.format(Locale.ROOT, "%.4f", s.jitterX());
        String jitterY = String.format(Locale.ROOT, "%.4f", s.jitterY());
        String viewLine = tr(translate, "skyboxblock.debug.f3.view", tr(translate, viewKey), s.slot(),
                tr(translate, phaseKey), extent, s.scaleX(), s.scaleY(), s.jitterX(), s.jitterY());
        out.add(PREFIX + viewLine);
        out.add(PREFIX + tr(translate, "skyboxblock.debug.f3.compatibility",
                tr(translate, s.iris() ? "skyboxblock.debug.f3.iris_pack"
                        : s.immersivePortals() ? "skyboxblock.debug.f3.iris_idle" : "skyboxblock.debug.f3.iris_off"),
                tr(translate, stateKey(s.sodium())),
                tr(translate, stateKey(s.distantHorizons())),
                tr(translate, s.portal() ? "skyboxblock.debug.f3.state_portal" : stateKey(s.immersivePortals()))));
        String reason = localizedReason(translate, s.itrpReason());
        String itrpKey=s.itrpStatus().equals("INJECTED")?"skyboxblock.debug.f3.itrp_mrt_background"
                :s.itrpStatus().equals("MRT-only")?"skyboxblock.debug.f3.itrp_mrt_only"
                :s.itrpStatus().equals("MRT")?"skyboxblock.debug.f3.itrp_mrt_only"
                :s.itrpStatus().equals("BG")?"skyboxblock.debug.f3.itrp_background_only":"skyboxblock.debug.f3.itrp_skipped";
        out.add(PREFIX + tr(translate,"skyboxblock.debug.f3.itrp",tr(translate,itrpKey),reason));
        Percentiles p = s.percentiles();
        String perf = p.samples() == 0 ? tr(translate, "skyboxblock.debug.f3.perf_warming")
                : tr(translate,"skyboxblock.debug.f3.performance_values",p.p50()/1_000_000D,p.p95()/1_000_000D,p.p99()/1_000_000D,
                        tr(translate,"skyboxblock.debug.f3.unit_ms"));
        out.add(PREFIX + tr(translate, "skyboxblock.debug.f3.performance",
                s.geometryDrawCalls(), bytes(translate,s.geometryUploadBytes()), perf));
        return List.copyOf(out);
    }

    static String shortText(String text,int max) {
        if(text==null||text.isBlank())return "";
        String clean=text.replace('\n',' ').replace('\r',' ').replace('\t',' ').trim();
        return clean.length()<=max ? clean : clean.substring(0,Math.max(0,max-1))+"~";
    }

    private static String localized(String key) {
        return net.minecraft.locale.Language.getInstance().getOrDefault(key);
    }

    private static String tr(java.util.function.Function<String, String> translate, String key, Object... args) {
        String pattern = translate.apply(key);
        return args.length == 0 ? pattern : String.format(Locale.ROOT, pattern, args);
    }

    private static String distanceSource(java.util.function.Function<String, String> translate, String source) {
        return switch (source) {
            case "file" -> tr(translate, "skyboxblock.debug.f3.distance_file");
            case "JVM" -> tr(translate, "skyboxblock.debug.f3.distance_jvm");
            default -> tr(translate, "skyboxblock.debug.f3.distance_default");
        };
    }
    private static String stateKey(boolean value) {
        return value ? "skyboxblock.debug.f3.state_on" : "skyboxblock.debug.f3.state_off";
    }
    private static String phaseName(String status) {
        return switch (status) {
            case "HIDDEN" -> "hidden";
            case "NO-PASS" -> "no_pass";
            case "EMPTY" -> "empty";
            case "FINAL" -> "final";
            case "COVERAGE" -> "coverage";
            case "OPAQUE" -> "opaque";
            default -> "skip";
        };
    }

    private static String localizedReason(java.util.function.Function<String, String> translate, String reason) {
        if (reason == null || reason.isBlank()) return "";
        String key = switch (reason) {
            case "hidden" -> "skyboxblock.debug.f3.reason_hidden";
            case "no faces" -> "skyboxblock.debug.f3.reason_no_faces";
            case "no Iris pack" -> "skyboxblock.debug.f3.reason_no_iris_pack";
            case "final not reached" -> "skyboxblock.debug.f3.reason_final_not_reached";
            case "opaque shader missing" -> "skyboxblock.debug.f3.reason_opaque_shader";
            case "opaque target unavailable" -> "skyboxblock.debug.f3.reason_opaque_target";
            case "sky canvas unavailable" -> "skyboxblock.debug.f3.reason_sky_canvas";
            case "final shader missing" -> "skyboxblock.debug.f3.reason_final_shader";
            case "DH depth unavailable" -> "skyboxblock.debug.f3.reason_dh_depth";
            case "final sky canvas unavailable" -> "skyboxblock.debug.f3.reason_final_canvas";
            case "final depth unavailable" -> "skyboxblock.debug.f3.reason_final_depth";
            default -> null;
        };
        String text = key == null ? shortText(reason, 32) : translate.apply(key);
        return text.isEmpty() ? "" : tr(translate, "skyboxblock.debug.f3.reason_suffix", text);
    }

    private static String bytes(java.util.function.Function<String,String> translate,long size) {
        if (size >= 1024L * 1024) return tr(translate,"skyboxblock.debug.f3.unit_mib",size/(1024D*1024));
        return size >= 1024 ? tr(translate,"skyboxblock.debug.f3.unit_kib",size/1024D)
                : tr(translate,"skyboxblock.debug.f3.unit_bytes",size);
    }

    record Percentiles(long p50, long p95, long p99, int samples) {
        private static final Percentiles EMPTY = new Percentiles(0, 0, 0, 0);
    }

    /** Fixed-size rolling sample window; nearest ranks are refreshed every 12 samples. */
    static final class FrameIntervals {
        private final long[] samples, scratch;
        private int next, size, sinceSort;
        private boolean hasPrevious;
        private long previous;
        private Percentiles cached = Percentiles.EMPTY;

        FrameIntervals(int capacity) {
            if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
            samples = new long[capacity];
            scratch = new long[capacity];
        }

        void sample(long now) {
            if (hasPrevious) add(now - previous);
            previous = now;
            hasPrevious = true;
        }

        void reset() {
            next = size = sinceSort = 0;
            hasPrevious = false;
            cached = Percentiles.EMPTY;
        }

        void add(long nanos) {
            if (nanos <= 0) return;
            samples[next] = nanos;
            next = (next + 1) % samples.length;
            size = Math.min(samples.length, size + 1);
            if (++sinceSort >= 12 || cached.samples() == 0 || (size == samples.length && cached.samples() < size)) {
                System.arraycopy(samples, 0, scratch, 0, size);
                Arrays.sort(scratch, 0, size);
                cached = new Percentiles(rank(50), rank(95), rank(99), size);
                sinceSort = 0;
            }
        }

        private long rank(int percentile) { return scratch[(percentile * size + 99) / 100 - 1]; }
        Percentiles percentiles() { return cached; }
    }
}
