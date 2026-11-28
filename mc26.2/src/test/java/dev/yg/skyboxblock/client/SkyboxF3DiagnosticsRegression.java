package dev.yg.skyboxblock.client;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Standalone F3 data/formatting regression; no Minecraft or GL classes are loaded. */
public final class SkyboxF3DiagnosticsRegression {
    private static int checks;

    public static void main(String[] args) throws Exception { run(); }

    static int run() throws Exception {
        checks = 0;
        Map<String,String> en=readLanguage("en_us"),zh=readLanguage("zh_cn");
        check(en.keySet().equals(zh.keySet()),"English and Simplified Chinese translation keys match");
        java.util.function.Function<String,String> english=key->en.getOrDefault(key,key);
        java.util.function.Function<String,String> chinese=key->zh.getOrDefault(key,key);
        var ring = new SkyboxF3Diagnostics.FrameIntervals(20);
        check(ring.percentiles().samples() == 0, "empty timing window");
        for (int i = 1; i <= 20; i++) ring.add(i);
        check(ring.percentiles().equals(new SkyboxF3Diagnostics.Percentiles(10,19,20,20)), "nearest ranks");
        for (int i = 21; i <= 32; i++) ring.add(i);
        check(ring.percentiles().equals(new SkyboxF3Diagnostics.Percentiles(22,31,32,20)), "rolling window evicts oldest samples");
        ring.reset();
        ring.sample(0);
        ring.sample(100);
        check(ring.percentiles().p50() == 100, "first interval is measured between successive frame starts");
        ring.reset();
        check(ring.percentiles().samples() == 0, "reset discards all previous samples");
        check(SkyboxF3Diagnostics.shortText("foo\nbar",5).equals("foo ~"), "single-line truncation");

        var styles = new ArrayList<>(List.of("skyboxblock:sky_block", "skyboxblock:some_very_long_custom_style", "black", "extra"));
        var root = snapshot(true,false,false,styles);
        styles.clear();
        check(root.styles().size() == 4, "snapshot detached from mutable producer list");
        var englishLines = SkyboxF3Diagnostics.format(root,2,1,english);
        var chineseLines = SkyboxF3Diagnostics.format(root,2,1,chinese);
        check(englishLines.size() == 8 && chineseLines.size() == 8, "compact summaries fit eight lines");
        for(String line:englishLines) check(line.startsWith("[skyboxblock] "), "every English row has the distinguishable mod prefix");
        for(String line:chineseLines) check(line.startsWith("[skyboxblock] "), "every Chinese row has the distinguishable mod prefix");
        check(englishLines.get(1).startsWith("[skyboxblock] Styles sky_block, some_very_long_c~")
                        && englishLines.get(1).contains(" +1") && !englishLines.get(1).contains("extra"), "at most three unique displayed styles and a total: "+englishLines.get(1));
        check(englishLines.get(0).contains("(server)") && englishLines.get(0).contains("256 (JVM)"), "English settings sources");
        check(chineseLines.get(0).contains("（服务器）") && chineseLines.get(0).contains("256（JVM 参数）"), "Chinese settings sources");
        check(englishLines.get(7).contains("Draw 3 upload 72 B") && englishLines.get(7).endsWith("1.0/2.0/3.0 ms"), "geometry draw count, localized upload unit and frame interval values");
        check(chineseLines.get(7).contains("绘制 3 上传 72 字节") && chineseLines.get(7).endsWith("1.0/2.0/3.0 毫秒"), "Chinese upload unit");
        check(englishLines.get(3).contains("PORTAL#2") && englishLines.get(3).contains("SHADOW#1")
                && chineseLines.get(3).contains("传送门#2") && chineseLines.get(3).contains("阴影#1"), "localized portal and shadow slots");
        check(chineseLines.get(1).startsWith("[skyboxblock] 风格 sky_block、some_very_long_c~、black 等 1 种"), "localized style heading and separator");
        String key="skyboxblock.skyboxRenderDistance", previous=System.getProperty(key);
        try {
            System.clearProperty(key);
            var props=new java.util.Properties();
            check(SkyboxConfig.fromProperties(props).skyboxRenderDistanceSource().equals("DEFAULT"), "unset distance uses default");
            props.setProperty("skyboxRenderDistance","512");
            var file=SkyboxConfig.fromProperties(props);
            check(file.skyboxRenderDistance()==512 && file.skyboxRenderDistanceSource().equals("FILE"), "valid file distance");
            System.setProperty(key,"768");
            var vm=SkyboxConfig.fromProperties(props);
            check(vm.skyboxRenderDistance()==768 && vm.skyboxRenderDistanceSource().equals("JVM"), "JVM distance overrides file");
            System.setProperty(key,"bad");
            var fallback=SkyboxConfig.fromProperties(props);
            check(fallback.skyboxRenderDistance()==256 && fallback.skyboxRenderDistanceSource().equals("DEFAULT"), "invalid override shows effective fallback source");
        } finally {
            if(previous==null) System.clearProperty(key); else System.setProperty(key,previous);
        }
        Object level = new Object(), other = new Object();
        check(!SkyboxF3Diagnostics.frameStart(level,false), "F3 hidden disables samples");
        SkyboxF3Diagnostics.publish(root,2,1);
        check(SkyboxF3Diagnostics.lines(level).size() == 1, "disabled publication ignored");
        check(SkyboxF3Diagnostics.frameStart(level,true), "F3 visible in a world enables samples");
        SkyboxF3Diagnostics.publish(snapshot(true,true,false,List.of()),-1,-1);
        SkyboxF3Diagnostics.publish(snapshot(true,false,true,List.of()),-1,-1);
        SkyboxF3Diagnostics.publish(snapshot(false,false,false,List.of()),-1,-1);
        check(SkyboxF3Diagnostics.lines(level).size() == 1, "portal, shadow and non-root views cannot replace root publication");
        SkyboxF3Diagnostics.publish(root,2,1);
        var displayed=SkyboxF3Diagnostics.lines(level);
        check(displayed.size() == 8 && displayed.stream().allMatch(line->line.startsWith("[skyboxblock] ")),
                "completed root publishes a prefixed localized summary for its world only");
        check(SkyboxF3Diagnostics.lines(other).size() == 1, "another world cannot see stale diagnostics");
        SkyboxF3Diagnostics.frameStart(other,true);
        check(SkyboxF3Diagnostics.lines(level).size() == 1 && SkyboxF3Diagnostics.percentiles().samples() == 0,
                "world change resets publication and interval window");
        SkyboxF3Diagnostics.frameStart(other,false);
        System.out.println("SkyboxF3DiagnosticsRegression: " + checks + " checks passed");
        return checks;
    }

    private static Map<String,String> readLanguage(String code) throws Exception {
        String path="/assets/skyboxblock/lang/"+code+".json";
        try(var stream=SkyboxF3DiagnosticsRegression.class.getResourceAsStream(path)) {
            if(stream==null) throw new AssertionError("missing language resource: "+path);
            var json=com.google.gson.JsonParser.parseReader(new InputStreamReader(stream,StandardCharsets.UTF_8)).getAsJsonObject();
            var translations=new java.util.LinkedHashMap<String,String>();
            json.entrySet().forEach(entry->translations.put(entry.getKey(),entry.getValue().getAsString()));
            return Map.copyOf(translations);
        }
    }

    private static SkyboxF3Diagnostics.Snapshot snapshot(boolean root, boolean portal, boolean shadow, List<String> styles) {
        return new SkyboxF3Diagnostics.Snapshot(root,portal,shadow,0,1,true,"SERVER",256,"JVM",styles,styles.size(),
                1,2,3,15,1,2,4,true,true,false,false,"SKIP","reason","FINAL","",1280,720,1,1,0,0,
                3,72,new SkyboxF3Diagnostics.Percentiles(1_000_000,2_000_000,3_000_000,20));
    }

    private static void check(boolean okay, String message) {
        checks++;
        if (!okay) throw new AssertionError(message);
    }
}
