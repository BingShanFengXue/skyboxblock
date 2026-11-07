package dev.yg.skyboxblock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.slf4j.Logger;

/** 把被测类的所有日志原样存下来，harness 结束时统一打印。 */
public final class CapturingLogger implements Logger {
    private static final List<String> LINES = Collections.synchronizedList(new ArrayList<>());

    public static List<String> lines() {
        synchronized (LINES) {
            return new ArrayList<>(LINES);
        }
    }

    public static void clear() {
        LINES.clear();
    }

    private static void add(String level, String format, Object... args) {
        String text = format;
        for (Object arg : args) {
            int at = text.indexOf("{}");
            if (at < 0) break;
            text = text.substring(0, at) + arg + text.substring(at + 2);
        }
        LINES.add(level + " " + text);
    }

    @Override public void warn(String format, Object arg) { add("WARN", format, arg); }

    @Override public void warn(String format) { add("WARN", format); }

    @Override public void warn(String format, Object arg1, Object arg2) { add("WARN", format, arg1, arg2); }

    @Override public void info(String format, Object arg) { add("INFO", format, arg); }

    @Override public void info(String format, Object arg1, Object arg2) { add("INFO", format, arg1, arg2); }
}
