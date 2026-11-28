package dev.yg.skyboxblock.client;

import dev.yg.skyboxblock.SkySkin;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.locale.Language;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.LongSupplier;

/** A read-only summary of the last completed world view. No GL queries or GPU waits. */
public final class SkyF3Diagnostics {
    private static final boolean SODIUM = FabricLoader.getInstance().isModLoaded("sodium");
    private static final FrameIntervals FRAME_INTERVALS = new FrameIntervals(120);
    private static final int MAX_STYLES = 3;
    private static final String ELLIPSIS = "...";
    private static WeakReference<Object> intervalLevel = new WeakReference<>(null);
    private static boolean captureThisFrame;

    private SkyF3Diagnostics() { }

    static void beginRootFrame() {
        Minecraft minecraft = Minecraft.getInstance();
        captureThisFrame = minecraft != null && minecraft.level != null
                && minecraft.getDebugOverlay().showDebugScreen();
        if (captureThisFrame && intervalLevel.get() != minecraft.level) {
            FRAME_INTERVALS.reset();
            intervalLevel = new WeakReference<>(minecraft.level);
        }
        FRAME_INTERVALS.observe(captureThisFrame, System::nanoTime);
    }

    static void endRootFrame() { captureThisFrame = false; }
    static boolean captureThisFrame() { return captureThisFrame; }

    /** Called at the F3 right-column text hook; never formats or allocates rows with F3 hidden. */
    public static List<String> appendLines(Minecraft minecraft, List<String> vanilla) {
        if (minecraft.level == null || !minecraft.getDebugOverlay().showDebugScreen()) return vanilla;
        int width = (minecraft.getWindow().getGuiScaledWidth() - 12) / 2;
        if (width <= 0) return vanilla;
        SkyWorldRenderer.Frame frame = SkyWorldRenderer.lastRoot();
        List<String> rows;
        if (frame == null || frame.level != minecraft.level || !frame.diagnosticsComplete) {
            var config = SkyboxConfig.get();
            rows = List.of(prefixed(tr("awaiting", "View: awaiting sample")));
        } else {
            rows = format(snapshot(frame));
        }
        var result = new ArrayList<String>(vanilla.size() + rows.size());
        result.addAll(vanilla);
        var font = minecraft.font;
        int ellipsisWidth = font.width(ELLIPSIS);
        for (String row : rows) {
            result.add(font.width(row) <= width ? row : width <= ellipsisWidth ? font.plainSubstrByWidth(row, width)
                    : font.plainSubstrByWidth(row, width - ellipsisWidth) + ELLIPSIS);
        }
        return result;
    }

    private static Snapshot snapshot(SkyWorldRenderer.Frame frame) {
        ArrayList<String> styles = new ArrayList<>(MAX_STYLES);
        for (SkySkin skin : frame.materialSkins()) {
            if (!styles.contains(skin.id())) styles.add(skin.id());
            if (styles.size() == MAX_STYLES) break;
        }
        String view = frame.shadow ? "SHADOW" : frame.portal ? "PORTAL" : frame.root ? "ROOT" : "CHILD";
        String phase = frame.shadow ? "SKIP" : !frame.renderSkybox ? "HIDDEN" : !frame.drawn ? "NO-PASS"
                : frame.floatCount == 0 ? "EMPTY" : frame.finalResolved ? "FINAL"
                : frame.ownSkyCoverageTexture > 0 ? "COVERAGE" : frame.opaqueRendered ? "OPAQUE" : "SKIP";
        boolean irisPack=ModCompat.IRIS && ModCompat.irisActive();
        String drawReason=frame.diagnosticDrawReason;
        if(drawReason==null && frame.opaqueRendered && !frame.finalResolved && irisPack)
            drawReason="final not reached";
        return new Snapshot(frame.renderSkybox, frame.renderControlledByServer, frame.renderDistance,
                frame.renderDistanceSource.label, view, frame.viewSlot, phase,
                frame.diagnosticPortalSlot, frame.diagnosticShadowSlot,
                frame.targetViewport[2], frame.targetViewport[3], frame.rasterScale.x, frame.rasterScale.y,
                frame.rasterJitter.x, frame.rasterJitter.y, styles,
                frame.normalCollected, frame.recoveredCollected, frame.floatCount/18, frame.culledFaces,
                frame.surfaceDrawCalls, frame.surfaceUploadBytes,
                frame.supplementColumnsVisited, frame.supplementChunksVisited, frame.supplementEntriesVisited,
                irisPack ? "pack" : ModCompat.IRIS ? "idle" : "off",
                SODIUM, ModCompat.DISTANT_HORIZONS, frame.distantOpaqueReady || frame.distantHdrReady,
                ModCompat.IMMERSIVE_PORTALS, frame.itrpMaterialWrites, frame.itrpBackgroundInjected,
                frame.itrpBridgeFailure, drawReason, FRAME_INTERVALS.percentilesMillis());
    }

    /** Uses the currently selected Minecraft client language. */
    private static String translate(String key) { return Language.getInstance().getOrDefault(key); }

    private static String tr(String key, String fallback) {
        String fullKey="skyboxblock.debug.f3."+key;
        String localized=translate(fullKey);
        return localized.equals(fullKey) ? fallback : localized;
    }
    private static String translated(Function<String,String> translator,String key,String fallback) {
        String fullKey="skyboxblock.debug.f3."+key;
        String localized=translator.apply(fullKey);
        return localized==null || localized.equals(fullKey) ? fallback : localized;
    }
    private static String prefixed(String row) { return "[skyboxblock] "+row; }

    /** Pure formatting of a captured view; translator injection keeps localization testable without a client. */
    static List<String> format(Snapshot s) { return format(s,SkyF3Diagnostics::translate); }
    static List<String> format(Snapshot s,Function<String,String> translator) {
        Objects.requireNonNull(translator,"translator");
        ArrayList<String> rows = new ArrayList<>(8);
        rows.add(String.format(Locale.ROOT,translated(translator,"settings","Skybox %s (%s) · RD %d (%s)"),
                translated(translator,s.enabled?"enabled":"disabled",s.enabled?"ON":"OFF"),
                translated(translator,s.server?"source_server":"source_local",s.server?"server":"local"),
                s.renderDistance,distanceSource(translator,s.distanceSource)));
        String view=translated(translator,switch(s.viewKind){
            case "SHADOW"->"view_shadow"; case "PORTAL"->"view_portal"; case "ROOT"->"view_root"; default->"view_child";
        },s.viewKind);
        String phase=translated(translator,"phase_"+switch(s.phase){
            case "HIDDEN"->"hidden"; case "NO-PASS"->"no_pass"; case "EMPTY"->"empty";
            case "FINAL"->"final"; case "COVERAGE"->"coverage"; case "OPAQUE"->"opaque"; default->"skip";
        },s.phase);
        String drawReason=localizedReason(translator,s.drawReason);
        String phaseDetail=phase+(drawReason.isEmpty()?"":" ("+drawReason+")");
        rows.add(String.format(Locale.ROOT,translated(translator,"view","%s #%d %s %s S %.2f/%.2f J %.4f/%.4f"),
                view,s.viewSlot,phaseDetail,resolution(s.width,s.height),
                s.scaleX,s.scaleY,s.jitterX,s.jitterY));
        rows.add(String.format(Locale.ROOT,translated(translator,"entities_faces","BE %d +%d · faces %d/-%d"),
                s.normalCollected,s.recoveredCollected,s.emittedFaces,s.culledFaces));
        String portal=s.portalSlot>=0?" · "+String.format(Locale.ROOT,translated(translator,"view_portal_slot","PORTAL#%d"),s.portalSlot):"";
        String shadow=s.shadowSlot>=0?" · "+String.format(Locale.ROOT,translated(translator,"view_shadow_slot","SHADOW#%d"),s.shadowSlot):"";
        rows.add(String.format(Locale.ROOT,translated(translator,"index","Index C/B/E %d/%d/%d%s"),
                s.indexColumns,s.indexBuckets,s.indexEntries,portal+shadow));
        rows.add(String.format(Locale.ROOT,translated(translator,"styles","Styles %s"),styles(s.styles,translator)));
        String iris=translated(translator,switch(s.iris){case "pack"->"iris_pack";case "idle"->"iris_idle";default->"iris_off";},s.iris);
        String sodium=translated(translator,s.sodium?"state_on":"state_off",s.sodium?"on":"off");
        String dh=translated(translator,s.distantDepth?"state_depth":s.distantHorizons?"state_loaded":"state_off",
                s.distantDepth?"depth":s.distantHorizons?"loaded":"off");
        String ip=translated(translator,s.portalSlot>=0||"PORTAL".equals(s.viewKind)?"state_portal":s.immersivePortals?"state_loaded":"state_off",
                s.portalSlot>=0||"PORTAL".equals(s.viewKind)?"portal":s.immersivePortals?"loaded":"off");
        rows.add(String.format(Locale.ROOT,translated(translator,"compatibility","Iris %s · Sodium %s · DH %s · IP %s"),iris,sodium,dh,ip));
        String reason=shortReason(s.itrpFailure);
        String itrpKey = s.itrpMaterial && s.itrpBackground ? "itrp_mrt_background"
                : s.itrpMaterial ? "itrp_mrt_only" : s.itrpBackground ? "itrp_background_only" : "itrp_skipped";
        String itrp = translated(translator, itrpKey,
                s.itrpMaterial && s.itrpBackground ? "MRT+BG" : s.itrpMaterial ? "MRT" : s.itrpBackground ? "BG" : "skip");
        boolean itrpSkipped = !s.itrpMaterial && !s.itrpBackground;
        if(reason.isEmpty() && itrpSkipped) {
            if(!s.enabled)reason=translated(translator,"reason_hidden","hidden");
            else if(s.emittedFaces==0)reason=translated(translator,"reason_no_faces","no faces");
            else if(!"pack".equals(s.iris))reason=translated(translator,"reason_no_iris_pack","no Iris pack");
        }
        String suffix=reason.isEmpty()?"":String.format(Locale.ROOT,translated(translator,"reason_suffix"," · %s"),reason);
        rows.add(String.format(Locale.ROOT,translated(translator,"itrp","ITRP %s%s"),itrp,suffix));
        String msUnit = translated(translator,"unit_ms","ms");
        String percentiles=s.intervals.count==0?translated(translator,"perf_warming","warming")
                :String.format(Locale.ROOT,translated(translator,"performance_values","%.1f/%.1f/%.1f %s"),
                        s.intervals.p50,s.intervals.p95,s.intervals.p99,msUnit);
        rows.add(String.format(Locale.ROOT,translated(translator,"performance","Draw %d ↑%s · P50/95/99 %s"),
                s.drawCalls,upload(translator,s.uploadBytes),percentiles));
        return rows.stream().map(SkyF3Diagnostics::prefixed).toList();
    }

    private static String distanceSource(Function<String,String> translator,String source) {
        String key=source==null?"default":switch(source.toLowerCase(Locale.ROOT)){
            case "file"->"file"; case "jvm"->"jvm"; default->"default";
        };
        String fallback=switch(key){case "file"->"file";case "jvm"->"JVM";default->"default";};
        return translated(translator,"distance_"+key,fallback);
    }
    private static String localizedReason(Function<String,String> translator,String reason) {
        if(reason==null||reason.isBlank())return "";
        String key=switch(reason){
            case "opaque shader missing"->"reason_opaque_shader";
            case "opaque target unavailable"->"reason_opaque_target";
            case "sky canvas unavailable"->"reason_sky_canvas";
            case "final shader missing"->"reason_final_shader";
            case "DH depth unavailable"->"reason_dh_depth";
            case "final sky canvas unavailable"->"reason_final_canvas";
            case "final depth unavailable"->"reason_final_depth";
            case "final not reached"->"reason_final_not_reached";
            default->null;
        };
        return key==null?shortReason(reason):translated(translator,key,shortReason(reason));
    }
    private static String resolution(int width, int height) {
        return width > 0 && height > 0 ? width + "x" + height : "-";
    }
    private static String upload(Function<String,String> translator,long bytes) {
        if(bytes<1024)return String.format(Locale.ROOT,translated(translator,"unit_bytes","%d B"),bytes);
        if(bytes<1024L*1024)return String.format(Locale.ROOT,translated(translator,"unit_kib","%.1f KiB"),bytes/1024.0);
        return String.format(Locale.ROOT,translated(translator,"unit_mib","%.1f MiB"),bytes/(1024.0*1024.0));
    }
    private static String onOff(boolean value) { return value ? "on" : "off"; }
    private static String styles(List<String> ids,Function<String,String> translator) {
        StringBuilder text = new StringBuilder();
        String separator=translated(translator,"styles_separator",", ");
        for (int i = 0, count = 0; i < ids.size() && count < MAX_STYLES; i++) {
            String id = ids.get(i);
            if (ids.subList(0, i).contains(id)) continue;
            if (count++ != 0) text.append(separator);
            if (id.startsWith("skyboxblock:")) id = id.substring("skyboxblock:".length());
            text.append(shortText(id, 18));
        }
        return text.isEmpty() ? "-" : text.toString();
    }
    static String shortReason(String reason) { return shortText(reason, 44); }
    private static String shortText(String text, int maximum) {
        if (text == null || text.isBlank()) return "";
        StringBuilder clean = new StringBuilder(maximum);
        boolean space = false;
        for (int i = 0; i < text.length() && clean.length() <= maximum; i++) {
            char c = text.charAt(i);
            if (c == '§' && i + 1 < text.length()) { i++; continue; }
            if (Character.isISOControl(c) || Character.isWhitespace(c)) { space = !clean.isEmpty(); continue; }
            if (space) { clean.append(' '); space = false; }
            clean.append(c);
        }
        if (clean.length() > maximum) return clean.substring(0, maximum - ELLIPSIS.length()) + ELLIPSIS;
        return clean.toString();
    }

    record Snapshot(boolean enabled, boolean server, int renderDistance, String distanceSource,
                    String viewKind, int viewSlot, String phase, int portalSlot, int shadowSlot,
                    int width, int height, float scaleX, float scaleY, float jitterX, float jitterY,
                    List<String> styles, int normalCollected, int recoveredCollected, int emittedFaces, int culledFaces,
                    int drawCalls, long uploadBytes, int indexColumns, int indexBuckets, int indexEntries,
                    String iris, boolean sodium, boolean distantHorizons, boolean distantDepth,
                    boolean immersivePortals, boolean itrpMaterial, boolean itrpBackground,
                    String itrpFailure, String drawReason, Percentiles intervals) { }

    record Percentiles(int count, double p50, double p95, double p99) {
        static final Percentiles EMPTY = new Percentiles(0, Double.NaN, Double.NaN, Double.NaN);
    }
    /** Recent root-frame intervals, with percentile work throttled to the displayed summary. */
    static final class FrameIntervals {
        private static final long REFRESH_NS = 250_000_000L;
        private final long[] samples, sorted;
        private int next, count;
        private long previous, computedAt;
        private boolean hasPrevious;
        private Percentiles cached = Percentiles.EMPTY;
        FrameIntervals(int capacity) {
            if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
            samples = new long[capacity];
            sorted = new long[capacity];
        }
        void observe(boolean enabled, LongSupplier clock) {
            if (!enabled) { reset(); return; }
            long now=clock.getAsLong();
            if (hasPrevious && now - previous > 0) {
                samples[next] = now - previous;
                next = (next + 1) % samples.length;
                count = Math.min(count + 1, samples.length);
            }
            previous = now;
            hasPrevious = true;
        }
        Percentiles percentilesMillis() {
            if (count == 0) return Percentiles.EMPTY;
            if (cached.count == 0 || previous - computedAt >= REFRESH_NS) {
                System.arraycopy(samples, 0, sorted, 0, count);
                Arrays.sort(sorted, 0, count);
                cached = new Percentiles(count, rank(50), rank(95), rank(99));
                computedAt = previous;
            }
            return cached;
        }
        private double rank(int percentile) { return sorted[(int) (((long)count*percentile+99)/100)-1] / 1_000_000.0; }
        void reset() { next = count = 0; hasPrevious = false; cached = Percentiles.EMPTY; }
    }
}
