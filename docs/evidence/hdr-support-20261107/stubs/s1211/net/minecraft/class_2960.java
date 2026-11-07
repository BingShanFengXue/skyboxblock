package net.minecraft;

/**
 * 1.21.1 生产 jar 里 CustomSkyRegistry 唯一用到的 MC 类型（intermediary 名 class_2960）。
 *
 * <p>只实现四个方法，语义与 ResourceLocation 对齐。测试目的是图片链路，不是注册表，
 * 所以这里用最小 stub 隔离 MC。</p>
 */
public final class class_2960 {
    private final String namespace;
    private final String path;

    private class_2960(String namespace, String path) {
        this.namespace = namespace;
        this.path = path;
    }

    /** ResourceLocation.parse(String)。 */
    public static class_2960 method_60654(String text) {
        int colon = text.indexOf(':');
        if (colon < 0) return new class_2960("minecraft", text);
        return new class_2960(text.substring(0, colon), text.substring(colon + 1));
    }

    /** ResourceLocation.fromNamespaceAndPath(String, String)。 */
    public static class_2960 method_60655(String namespace, String path) {
        return new class_2960(namespace, path);
    }

    /** getNamespace()。 */
    public String method_12836() {
        return namespace;
    }

    /** getPath()。 */
    public String method_12832() {
        return path;
    }

    @Override public String toString() {
        return namespace + ":" + path;
    }
}
