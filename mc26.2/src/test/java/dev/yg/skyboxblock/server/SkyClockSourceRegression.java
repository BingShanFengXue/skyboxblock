package dev.yg.skyboxblock.server;

import dev.yg.skyboxblock.SkyTimeClock;
import dev.yg.skyboxblock.client.SkyRenderMath;
import org.objectweb.asm.*;

/** Guard the unit boundary between native 26.2 solar degrees, phase, and command-clock ticks. */
public final class SkyClockSourceRegression {
    private static int checks;
    public static void main(String[] args) throws Exception {
        for (int tick = 0; tick < 24000; tick++) {
            float degrees = SkyRenderMath.simulatedPhase(tick, 0) * 360F;
            double captured = SkyboxServerConfig.automaticTicksFromSunAngle(degrees);
            double error = Math.abs(captured-tick);
            error = Math.min(error, 24000-error);
            check(error < .02, "native solar angle captures the displayed time: tick="+tick+" error="+error);
        }
        double midnight = SkyboxServerConfig.automaticTicksFromSunAngle(180F);
        check(Math.abs(midnight-18000) < .01, "lock-current at midnight stays midnight");
        check(Math.abs(SkyTimeClock.ticksForPhase(18000)-midnight) > 10000,
                "negative control: ticks incorrectly treated as phase would jump to noon");
        boolean[] angleSource = {false}, conversion = {false}, wrongSource = {false};
        try (var input=SkyClockSourceRegression.class.getClassLoader().getResourceAsStream(
                "dev/yg/skyboxblock/server/SkyboxServerConfig.class")) {
            if(input==null) throw new AssertionError("production class missing");
            new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int access,String name,String descriptor,String signature,String[] exceptions) {
                    if(!name.equals("automaticTicks")) return null;
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override public void visitFieldInsn(int opcode,String owner,String name,String desc) {
                            if(owner.equals("net/minecraft/world/attribute/EnvironmentAttributes") && name.equals("SUN_ANGLE")) angleSource[0]=true;
                        }
                        @Override public void visitMethodInsn(int opcode,String owner,String name,String desc,boolean isInterface) {
                            if(name.equals("automaticTicksFromSunAngle")) conversion[0]=true;
                            if(name.equals("getDefaultClockTime")) wrongSource[0]=true;
                        }
                    };
                }
            },ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);
        }
        check(angleSource[0] && conversion[0] && !wrongSource[0], "production AUTO capture reads native solar degrees and converts units exactly once");
        System.out.println("SkyClockSourceRegression: "+checks+" checks passed (native angle-to-command-clock units and midnight negative control)");
    }
    private static void check(boolean condition,String message) { checks++; if(!condition) throw new AssertionError(message); }
}
