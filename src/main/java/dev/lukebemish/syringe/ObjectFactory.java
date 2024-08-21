package dev.lukebemish.syringe;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

public interface ObjectFactory {
    <T> T newInstance(Class<T> clazz, Object... args);

    ObjectFactory newObjectFactory(Options options);

    class Options {
        private Options() {}

        final Map<Class<?>, Object> instances = new HashMap<>();
        final Map<Class<?>, Provider<?>> providers = new HashMap<>();

        public static Options create() {
            return new Options();
        }

        public <T> void bindService(Class<T> clazz, T instance) {
            instances.put(clazz, instance);
        }

        public <T> Consumer<T> bindServiceProvider(Class<T> clazz) {
            var provider = new DelayedProvider<T>(clazz);
            providers.put(clazz, provider);
            return provider::set;
        }
    }
}
