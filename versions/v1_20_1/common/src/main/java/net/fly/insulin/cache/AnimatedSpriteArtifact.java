package net.fly.insulin.cache;

import java.util.Objects;

public record AnimatedSpriteArtifact(String atlas, String sprite) {

    public AnimatedSpriteArtifact {
        atlas = requireText(atlas, "atlas");
        sprite = requireText(sprite, "sprite");
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
