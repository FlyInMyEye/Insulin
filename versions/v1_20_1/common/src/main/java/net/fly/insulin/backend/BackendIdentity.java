package net.fly.insulin.backend;

import java.util.Objects;

public record BackendIdentity(String id, String rendererVersion, int codecVersion) {

    public BackendIdentity {
        id = requireText(id, "id");
        rendererVersion = requireText(rendererVersion, "rendererVersion");
        if (codecVersion < 1) {
            throw new IllegalArgumentException("codecVersion must be positive");
        }
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
