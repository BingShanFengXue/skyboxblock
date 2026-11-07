package org.slf4j;

/** 只保留被测类实际调用的重载，记录到内存列表供 harness 打印。 */
public interface Logger {
    void warn(String format, Object arg);

    void warn(String format);

    void warn(String format, Object arg1, Object arg2);

    void info(String format, Object arg);

    void info(String format, Object arg1, Object arg2);
}
