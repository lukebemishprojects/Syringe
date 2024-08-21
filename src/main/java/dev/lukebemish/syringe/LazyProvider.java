package dev.lukebemish.syringe;

import org.jspecify.annotations.Nullable;

import java.util.Objects;
import java.util.function.Supplier;

public class LazyProvider<T> implements Provider<T> {
    private final Supplier<T> supplier;
    private final EvaluatedType type;
    private volatile @Nullable T value;

    public LazyProvider(Supplier<T> supplier, EvaluatedType type) {
        this.supplier = supplier;
        this.type = type;
    }

    @Override
    public T get() {
        if (value == null) {
            synchronized (this) {
                if (value == null) {
                    T t = Objects.requireNonNull(supplier.get(), "Supplier in lazy provider of type "+type+" returned null");
                    value = t;
                    return t;
                }
            }
        }
        return value;
    }

    @Override
    public boolean isPresent() {
        return true;
    }
}
