package net.fly.insulin.cache;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

public final class ResourceChecksum {

    private ResourceChecksum() {
    }

    public static String compute(ResourceManager manager) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[32 * 1024];
            manager.listResources("", location -> true).entrySet().stream()
                .sorted(Comparator.comparing(entry -> entry.getKey().toString()))
                .forEach(entry -> update(digest, entry.getKey().toString(), entry.getValue(), buffer));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void update(MessageDigest digest, String location, Resource resource, byte[] buffer) {
        digest.update(location.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        try (InputStream input = resource.open()) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to checksum " + location, exception);
        }
        digest.update((byte) 0);
    }
}
