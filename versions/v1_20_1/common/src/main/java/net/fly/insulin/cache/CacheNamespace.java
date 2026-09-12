package net.fly.insulin.cache;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.storage.LevelResource;

public final class CacheNamespace {

    private CacheNamespace() {
    }

    public static String forLevel(Minecraft client, ClientLevel level) {
        if (level == null) {
            return null;
        }

        String world;
        IntegratedServer integrated = client.getSingleplayerServer();
        if (integrated != null) {
            world = integrated.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().toString();
        } else {
            ServerData server = client.getCurrentServer();
            world = server == null ? "unknown" : server.ip;
        }

        String identity = world + '|' + level.dimension().location();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(identity.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
