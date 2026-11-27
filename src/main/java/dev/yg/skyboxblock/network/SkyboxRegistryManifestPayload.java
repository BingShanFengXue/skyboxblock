package dev.yg.skyboxblock.network;

import dev.yg.skyboxblock.custom.CustomSkyRegistry;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** Bounded login-query protocol for comparing dynamic block IDs before registry sync begins. */
public final class SkyboxRegistryManifestPayload {
    public static final ResourceLocation CHANNEL = ResourceLocation.fromNamespaceAndPath("skyboxblock", "custom_registry_manifest_v1");
    public static final int PROTOCOL = 1;
    public static final int MAX_IDS = 64;
    public static final int MAX_ID_CHARS = 256;
    private static final Pattern ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    private SkyboxRegistryManifestPayload() { }

    /** Sorted IDs that are actually registered locally; image paths and bytes never leave the client/server. */
    public static List<String> localIds() {
        TreeSet<String> ids = new TreeSet<>();
        for (var style : CustomSkyRegistry.styles()) {
            if (!validId(style.id())) throw new IllegalStateException("Invalid registered sky ID: " + style.id());
            ids.add(style.id());
        }
        if (ids.size() > MAX_IDS) throw new IllegalStateException("Custom sky ID manifest exceeds " + MAX_IDS);
        return List.copyOf(ids);
    }

    /** Login query payload: protocol tag and the server's registered custom block IDs. */
    public static FriendlyByteBuf encode(List<String> ids) {
        List<String> canonical = canonicalIds(ids);
        FriendlyByteBuf out = new FriendlyByteBuf(Unpooled.buffer());
        out.writeByte(PROTOCOL);
        out.writeVarInt(canonical.size());
        for (String id : canonical) out.writeUtf(id, MAX_ID_CHARS);
        return out;
    }

    /** Reads a bounded manifest and requires full payload consumption. */
    public static List<String> decode(FriendlyByteBuf input) {
        if (input.readableBytes() > maxPayloadBytes()) throw new IllegalArgumentException("Manifest payload exceeds limit");
        int protocol = input.readUnsignedByte();
        if (protocol != PROTOCOL) throw new IllegalArgumentException("Unsupported manifest protocol: " + protocol);
        int count = input.readVarInt();
        if (count < 0 || count > MAX_IDS) throw new IllegalArgumentException("Invalid manifest ID count: " + count);
        List<String> ids = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String id = input.readUtf(MAX_ID_CHARS);
            if (!validId(id)) throw new IllegalArgumentException("Invalid custom block ID in manifest");
            ids.add(id);
        }
        if (input.isReadable()) throw new IllegalArgumentException("Trailing manifest payload data");
        return canonicalIds(ids);
    }

    private static int maxPayloadBytes() { return 1 + 5 + MAX_IDS * (MAX_ID_CHARS + 3); }
    private static boolean validId(String id) { return id != null && id.length() <= MAX_ID_CHARS && ID.matcher(id).matches(); }

    private static List<String> canonicalIds(List<String> source) {
        if (source == null || source.size() > MAX_IDS) throw new IllegalArgumentException("Invalid manifest ID count");
        TreeSet<String> sorted = new TreeSet<>();
        for (String id : source) {
            if (!validId(id)) throw new IllegalArgumentException("Invalid custom block ID in manifest");
            if (!sorted.add(id)) throw new IllegalArgumentException("Duplicate custom block ID in manifest");
        }
        return List.copyOf(sorted);
    }

    /** Human-readable ID-only difference; null means the two registration sets match. */
    public static String mismatchReport(List<String> serverIds, List<String> clientIds) {
        List<String> server = canonicalIds(serverIds), client = canonicalIds(clientIds);
        TreeSet<String> serverOnly = new TreeSet<>(server);
        serverOnly.removeAll(client);
        TreeSet<String> clientOnly = new TreeSet<>(client);
        clientOnly.removeAll(server);
        if (serverOnly.isEmpty() && clientOnly.isEmpty()) return null;
        return "[Sky Box Block] Custom block registry differs before registry sync. "
                + "Server-only IDs: " + serverOnly + "; client-only IDs: " + clientOnly
                + ". Match the enabled skyboxblock custom block ID sets on both sides.";
    }

    public static String unsupportedClientReport(List<String> serverIds) {
        List<String> server = canonicalIds(serverIds);
        if (server.isEmpty()) return null;
        return "[Sky Box Block] This server registers custom sky block IDs " + server
                + ", but the client does not support the pre-registry manifest handshake. Install the same mod/config.";
    }
}
