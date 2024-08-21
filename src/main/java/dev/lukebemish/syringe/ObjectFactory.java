package dev.lukebemish.syringe;

import java.util.HashMap;
import java.util.Map;

public interface ObjectFactory {
    <T> T newInstance(Class<T> clazz, Object... args);

    Object newInstance(EvaluatedType type, Object... args);

    ObjectFactory newObjectFactory(Configuration configuration);

    <T> T findService(Class<T> clazz);

    class Configuration {
        private Configuration() {}

        record ToCreate<T>(Class<? extends T> implementation, Object[] args) {}

        final Map<EvaluatedType, ToCreate<Object>> toCreateServices = new HashMap<>();
        final Map<EvaluatedType, ToCreate<Object>> toCreateServiceTypes = new HashMap<>();
        final Map<EvaluatedType, Provider<?>> providers = new HashMap<>();

        final Map<Class<?>, ToCreate<Instantiator<?>>> toCreateInstantiators = new HashMap<>();
        final Map<Class<?>, ToCreate<Instantiator<?>>> toCreateInstantiatorTypes = new HashMap<>();
        final Map<Class<?>, Instantiator<?>> instantiators = new HashMap<>();

        public static Configuration create() {
            return new Configuration();
        }

        public <T> void bindService(Class<T> clazz, T instance) {
            providers.put(EvaluatedType.of(clazz), new ConstantProvider<>(instance));
        }

        public <T> void bindService(Class<T> clazz, Class<? extends T> implementation, Object... args) {
            toCreateServices.put(EvaluatedType.of(clazz), new ToCreate<>(implementation, args));
        }

        public <T> void bindServiceType(Class<T> clazz, Class<? extends T> implementation, Object... args) {
            toCreateServiceTypes.put(EvaluatedType.of(clazz), new ToCreate<>(implementation, args));
        }

        public <T> void bindService(Class<T> clazz) {
            bindService(clazz, clazz);
        }

        public <T> void bindServiceType(Class<T> clazz) {
            bindServiceType(clazz, clazz);
        }

        public <T> void bindInstantiator(Class<T> instanceType, Instantiator<T> instantiator) {
            instantiators.put(instanceType, instantiator);
        }

        public <T> void bindInstantiator(Class<T> instanceType, Class<? extends Instantiator<T>> implementation, Object... args) {
            toCreateInstantiators.put(instanceType, new ToCreate<>(implementation, args));
        }

        public <T> void bindInstantiatorType(Class<T> instanceType, Class<? extends Instantiator<T>> implementation, Object... args) {
            toCreateInstantiatorTypes.put(instanceType, new ToCreate<>(implementation, args));
        }
    }
}
