package net.minecraft.resources;

/**
 * 26.2 生产 jar 里 CustomSkyRegistry 唯一用到的 MC 类型（Mojang 官方映射命名）。
 *
 * <p>只实现三个方法，语义与 Identifier 对齐：parse 支持缺省命名空间 minecraft，
 * fromNamespaceAndPath 原样拼装。</p>
 */
public final class Identifier {
    private final String namespace;
    private final String path;

    private Identifier(String namespace, String path) {
        this.namespace = namespace;
        this.path = path;
    }

    /** Identifier.parse(String)。 */
    public static Identifier parse(String text) {
        int colon = text.indexOf(':');
        if (colon < 0) return new Identifier("minecraft", text);
        return new Identifier(text.substring(0, colon), text.substring(colon + 1));
    }

    /** Identifier.fromNamespaceAndPath(String, String)。 */
    public static Identifier fromNamespaceAndPath(String namespace, String path) {
        return new Identifier(namespace, path);
    }

    public String getNamespace() {
        return namespace;
    }

    public String getPath() {
        return path;
    }

    @Override public String toString() {
        return namespace + ":" + path;
    }
}
