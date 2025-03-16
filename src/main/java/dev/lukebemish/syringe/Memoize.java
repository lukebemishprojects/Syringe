package dev.lukebemish.syringe;

import java.util.function.Supplier;

final class Memoize<T> implements Lazy<T> {
    private Supplier<T> supplier;
    private boolean initialized = false;
    private boolean initializing = false;
    private T value = null;

    public Memoize(Supplier<T> supplier) {
        this.supplier = supplier;
    }

    @Override
    public T get() {
        if (initialized) {
            return value;
        } else {
            synchronized (this) {
                if (initialized) {
                    return value;
                } else if (initializing) {
                    throw new IllegalStateException("Circular dependency in memoized supplier detected");
                } else {
                    initializing = true;
                    T t = supplier.get();
                    supplier = null;
                    value = t;
                    initialized = true;
                    initializing = false;
                    return t;
                }
            }
        }
    }
}
