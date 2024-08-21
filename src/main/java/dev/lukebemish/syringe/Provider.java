package dev.lukebemish.syringe;

public interface Provider<T> {
    T get();

    boolean isPresent();
}
