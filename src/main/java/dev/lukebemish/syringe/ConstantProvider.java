package dev.lukebemish.syringe;

import java.util.Objects;

final class ConstantProvider<T> implements Provider<T> {
    private final T value;

    ConstantProvider(T value) {
        this.value = Objects.requireNonNull(value);
    }

    @Override
    public T get() {
        return value;
    }

    @Override
    public boolean isPresent() {
        return true;
    }
}
