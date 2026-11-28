package dev.yg.skyboxblock.client;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/** F3 text/telemetry regression, with no game or GL initialization. */
public final class SkyF3DiagnosticsRegression {
    private static int checks;
    private SkyF3DiagnosticsRegression() { }

    public static void main(String[] args) throws Exception { run(); }

    static int run() throws Exception {
        checks=0;
        Map<String,String> en=readLanguage("en_us"),zh=readLanguage("zh_cn");
        check(en.keySet().equals(zh.keySet()),"English and Simplified Chinese have the same translation keys");
        java.util.function.Function<String,String> english=key->en.getOrDefault(key,key);
        java.util.function.Function<String,String> chinese=key->zh.getOrDefault(key,key);
        SkyF3Diagnostics.FrameIntervals samples=new SkyF3Diagnostics.FrameIntervals(20);
        samples.observe(false,()->{throw new AssertionError("hidden HUD touched the sample clock");});
        check(samples.percentilesMillis().count()==0,"hidden interval history is empty");
        long time=0;
        for(int i=0;i<=21;i++) {
            time+=i*1_000_000L;
            long stamp=time;
            samples.observe(true,()->stamp);
        }
        var statistics=samples.percentilesMillis();
        check(statistics.count()==20 && statistics.p50()==11 && statistics.p95()==20 && statistics.p99()==21,
                "rolling ring retains the latest 20 intervals, nearest-rank P50/P95/P99");
        samples.observe(false,()->{throw new AssertionError("closed HUD touched the sample clock");});
        samples.observe(true,()->1_000_000_000L);
        check(samples.percentilesMillis().count()==0,"reopened HUD starts a new interval without measuring the hidden gap");
        var snapshot=new SkyF3Diagnostics.Snapshot(
                true,true,512,"JVM","ROOT",0,"FINAL",2,1,1920,1080,.5f,.5f,.0002f,-.0003f,
                List.of("skyboxblock:galaxy_sky_block","skyboxblock:galaxy_sky_block","example:one","example:two","example:four"),
                300,4,80,1744,3,2048,10,22,330,"pack",true,true,true,true,true,false,
                "adapter-failed\n§creflection-detail-abcdefghijklmnopqrstuvwxyz-1234567890","",statistics);
        List<String> rows=SkyF3Diagnostics.format(snapshot,english);
        List<String> chineseRows=SkyF3Diagnostics.format(snapshot,chinese);
        check(rows.size()==8 && rows.get(0).equals("[skyboxblock] Skybox ON (server) · RD 512 (JVM)"),"English F3 settings are localized and prefixed");
        check(chineseRows.get(0).equals("[skyboxblock] 天空盒 开启（服务器）· 距离 512（JVM 参数）"),"Simplified Chinese F3 settings are localized");
        for(String line:rows)check(line.startsWith("[skyboxblock] "),"each English F3 line has the requested prefix");
        for(String line:chineseRows)check(line.startsWith("[skyboxblock] "),"each Chinese F3 line has the requested prefix");
        check(rows.get(1).startsWith("[skyboxblock] ROOT #0 FINAL 1920x1080 S 0.50/0.50"),"actual view identifier, target and raster scale");
        check(rows.get(2).contains("BE 300 +4") && rows.get(2).contains("80/-1744"),"show normal, recovered, emitted and culled independently");
        check(rows.get(3).contains("10/22/330") && rows.get(3).contains("PORTAL#2") && rows.get(3).contains("SHADOW#1"),"index work and observed subview slots");
        check(rows.get(4).equals("[skyboxblock] Styles galaxy_sky_block, example:one, example:two"),"English styles are separated and de-duplicated");
        check(chineseRows.get(4).equals("[skyboxblock] 风格 galaxy_sky_block、example:one、example:two"),"Chinese styles use the localized separator");
        check(rows.get(6).startsWith("[skyboxblock] ITRP MRT only") && rows.get(6).contains("adapter-failed reflection-detail-") && rows.get(6).endsWith("...")
                && !rows.get(6).contains("\n") && !rows.get(6).contains("§"),"show localized bridge state and a bounded single-line reason: "+rows.get(6));
        check(chineseRows.get(6).startsWith("[skyboxblock] ITRP 仅MRT ·"),"ITRP state is localized in Chinese");
        check(rows.get(7).contains("Draw 3 upload 2.0 KiB") && rows.get(7).contains("P50/95/99 11.0/20.0/21.0 ms"),"English performance units are formatted");
        check(chineseRows.get(7).contains("绘制 3 上传 2.0 KiB") && chineseRows.get(7).contains("帧耗时 P50/95/99 11.0/20.0/21.0 毫秒"),"performance labels and units are localized");
        var skipped=SkyF3Diagnostics.format(new SkyF3Diagnostics.Snapshot(
                true,false,256,"default","ROOT",0,"SKIP",-1,-1,0,0,1f,1f,0f,0f,
                List.of(),1,0,1,5,0,0,0,0,0,"pack",true,false,false,false,false,false,
                "","opaque target unavailable",SkyF3Diagnostics.Percentiles.EMPTY),english);
        check(skipped.get(1).startsWith("[skyboxblock] ROOT #0 SKIP (opaque target unavailable)"),"show the primary draw-stage reason");
        String former=System.getProperty("skyboxblock.skyboxRenderDistance");
        Properties props=new Properties();
        try {
            System.clearProperty("skyboxblock.skyboxRenderDistance");
            check(SkyboxConfig.fromProperties(props).skyboxRenderDistanceSource()==SkyboxConfig.DistanceSource.DEFAULT,"unset distance has default source");
            props.setProperty("skyboxRenderDistance","256");
            check(SkyboxConfig.fromProperties(props).skyboxRenderDistanceSource()==SkyboxConfig.DistanceSource.FILE,"explicit default-valued file entry retains source");
            System.setProperty("skyboxblock.skyboxRenderDistance","512");
            var config=SkyboxConfig.fromProperties(props);
            check(config.skyboxRenderDistance()==512 && config.skyboxRenderDistanceSource()==SkyboxConfig.DistanceSource.JVM,"JVM source wins over file");
            System.setProperty("skyboxblock.skyboxRenderDistance","bad");
            check(config.skyboxRenderDistanceSource()==SkyboxConfig.DistanceSource.JVM,"source stays coupled to the cached distance");
            var invalid=SkyboxConfig.fromProperties(props);
            check(invalid.skyboxRenderDistance()==256 && invalid.skyboxRenderDistanceSource()==SkyboxConfig.DistanceSource.DEFAULT,
                    "invalid JVM override falls back to default without claiming it came from the file");
        } finally {
            if(former==null)System.clearProperty("skyboxblock.skyboxRenderDistance");
            else System.setProperty("skyboxblock.skyboxRenderDistance",former);
        }
        System.out.println("SkyF3DiagnosticsRegression: PASS "+checks+" checks");
        return checks;
    }

    private static Map<String,String> readLanguage(String code) throws Exception {
        String path="/assets/skyboxblock/lang/"+code+".json";
        try(var stream=SkyF3DiagnosticsRegression.class.getResourceAsStream(path)) {
            if(stream==null) throw new AssertionError("missing language resource: "+path);
            var json=com.google.gson.JsonParser.parseReader(new InputStreamReader(stream,StandardCharsets.UTF_8)).getAsJsonObject();
            var translations=new java.util.LinkedHashMap<String,String>();
            json.entrySet().forEach(entry->translations.put(entry.getKey(),entry.getValue().getAsString()));
            return Map.copyOf(translations);
        }
    }

    private static void check(boolean condition,String reason) {
        checks++;
        if(!condition)throw new AssertionError(reason);
    }
}
