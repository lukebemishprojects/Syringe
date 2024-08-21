package dev.lukebemish.syringe;

import org.jspecify.annotations.Nullable;

import java.util.Objects;

final class DelayedProvider<T> implements Provider<T> {
    private volatile @Nullable T value;
    private final EvaluatedType type;

    DelayedProvider(EvaluatedType type) {
        this.type = type;
    }

    @Override
    public T get() {
        return Objects.requireNonNull(this.value, "Provider value of type "+ type +" not yet present");
    }

    void set(T value) {
        if (this.value != null) {
            throw new IllegalStateException("Provider value of type "+ type +" already present");
        }
        this.value = value;
    }

    @Override
    public boolean isPresent() {
        return this.value != null;
    }
}
