package dev.yg.skyboxblock;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/** The GPU coverage owner must distinguish all custom IDs from built-ins and empty pixels. */
public final class SkyMaterialIdentityRegression {
    private static int checks;
    private static void check(boolean value, String description) {
        checks++;
        if (!value) throw new AssertionError(description);
    }
    public static void main(String[] args) throws Exception {
        Set<Integer> owners = new HashSet<>();
        for (SkyStyle style : SkyStyle.values()) {
            SkySkin skin = style.skin();
            check(skin.material() == style.ordinal(), "built-in ordinal unchanged: " + style);
            check(!skin.isCustom(), "built-in classification unchanged");
            check(owners.add(skin.material() + 1), "unique built-in coverage");
        }
        check(SkyStyle.BLACK.skin().material() + 1 == 3, "dark-meter black owner");
        check(SkyStyle.GALAXY.skin().material() + 1 == 7, "dark-meter galaxy owner");
        check(SkyStyle.APOCALYPSE.skin().material() + 1 == 9, "dark-meter apocalypse owner");
        var image = SkyboxBlockMod.id("textures/sky/test.png");
        for (int index = 0; index < 64; index++) {
            String id = "skyboxblock:owner_test_" + index;
            SkySkin skin = SkySkin.custom(id, image);
            check(skin.isCustom(), "custom classification retained");
            check(skin.shaderMode() == 3 && skin.texture().equals(image), "panorama sampling retained");
            check(skin.material() >= SkyStyle.values().length, "disjoint custom range");
            check(owners.add(skin.material() + 1), "unique custom coverage, including shared texture");
            check(skin.material() + 1 > 0, "never alias empty coverage");
            check((int)(float)(skin.material() + 1) == skin.material() + 1, "exact RG32F owner");
            check(SkySkin.custom(id, image).equals(skin), "recreated ID retains owner");
            check(SkySkin.custom(id, SkyboxBlockMod.id("textures/sky/changed.png")).material() == skin.material(),
                    "resource reload does not reassign identity");
        }
        check(owners.size() == SkyStyle.values().length + 64, "all registered styles have separate owners");
        int front = SkySkin.custom("skyboxblock:owner_test_0", image).material();
        int rear = SkySkin.custom("skyboxblock:owner_test_1", image).material();
        check(Math.abs((float)(front + 1) - (rear + 1)) > .25F, "rear batch fails front owner tolerance");
        check((-1 + 1) == 0, "legacy negative control collides with empty sentinel");
        String report = "PASS: " + checks + " material identity assertions\n";
        if (args.length > 0) {
            Path file = Path.of(args[0]);
            Files.createDirectories(file.toAbsolutePath().getParent());
            Files.writeString(file, report, StandardCharsets.UTF_8);
        }
        System.out.print(report);
    }
}
