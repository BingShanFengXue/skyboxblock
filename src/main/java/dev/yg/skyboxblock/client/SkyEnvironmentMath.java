package dev.yg.skyboxblock.client;

import dev.yg.skyboxblock.SkyEnvironment;

/** Pure calculations shared by the private scene and its regression tests. */
final class SkyEnvironmentMath {
    private SkyEnvironmentMath() { }
    static float angle(float degrees) {
        float result=(float)(((double)degrees%360.0+360.0)%360.0);
        return result>=360F ? 0F : result;
    }
    static boolean hasFog(SkyEnvironment env) {
        return env.has("fog_start_distance") || env.has("fog_end_distance") || env.has("sky_fog_end_distance");
    }
    static float fogStart(SkyEnvironment env,float fallback) {
        return env.number("fog_start_distance",fallback);
    }
    static float fogEnd(SkyEnvironment env,float fallback) {
        float end=env.has("fog_end_distance") ? Math.max(96F,env.number("fog_end_distance",fallback)) : fallback;
        if (!env.has("sky_fog_end_distance")) return end;
        return env.number("sky_fog_end_distance",fallback);
    }
    static float visibility(float distance,float start,float end) {
        if(end<=start) return distance<end ? 1F : 0F;
        float t=Math.max(0F,Math.min(1F,(distance-start)/(end-start)));
        return 1F-t*t*(3F-2F*t);
    }
    static float cloudHeight(SkyEnvironment env,float legacyNominalHeight) {
        // The environment attribute is final Y, whereas legacy cloudheight adds vanilla's 0.33.
        return env.has("cloud_height") ? env.number("cloud_height",192.33F)-.33F : legacyNominalHeight;
    }
    static float cloudEnd(SkyEnvironment env,float legacyEnd) {
        return env.number("cloud_fog_end_distance",legacyEnd);
    }
    static float cloudStart(SkyEnvironment env,float legacyStart,float end) {
        float start=env.has("cloud_fog_end_distance") ? end/3F : legacyStart;
        return env.number("fog_start_distance",start);
    }
}