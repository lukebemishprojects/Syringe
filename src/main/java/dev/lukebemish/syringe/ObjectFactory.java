package dev.lukebemish.syringe;

public interface ObjectFactory {
    <T> T newInstance(Class<T> clazz);
}
