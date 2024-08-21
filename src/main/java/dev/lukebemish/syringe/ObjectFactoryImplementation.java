package dev.lukebemish.syringe;

import org.apache.commons.lang3.reflect.TypeUtils;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

class ObjectFactoryImplementation implements ObjectFactory {
    private static final Map<Class<?>, InjectedImplementation> creators = new ConcurrentHashMap<>();

    private final @Nullable ClassLoader classLoader;
    private final @Nullable ObjectFactoryImplementation parent;

    private final Map<Type, Object> services = new HashMap<>();
    private final Map<Type, Provider<?>> serviceProviders = new HashMap<>();

    ObjectFactoryImplementation(@Nullable ClassLoader classLoader, @Nullable ObjectFactoryImplementation parent) {
        this.classLoader = classLoader;
        this.parent = parent;
        services.put(ObjectFactory.class, this);
    }

    void registerServiceInstance(Type type, Object instance) {
        services.put(type, instance);
    }

    Consumer<Object> registerServiceProviderInstance(Type type) {
        var provider = new DelayedProvider<>(type);
        services.put(TypeUtils.parameterize(Provider.class, type), provider);
        serviceProviders.put(type, provider);
        return provider::set;
    }

    private @Nullable Object serviceInstance(Type type) {
        var instance = services.get(type);
        if (instance != null) {
            return instance;
        }
        if (parent != null) {
            return parent.serviceInstance(type);
        }
        return null;
    }

    private @Nullable Object serviceViaProvider(Type type) {
        var provider = serviceProviders.get(type);
        if (provider != null) {
            return provider.get();
        }
        if (parent != null) {
            return parent.serviceViaProvider(type);
        }
        return null;
    }

    private @Nullable Object findServiceOfType(Type type) {
        var instance = serviceInstance(type);
        if (instance != null) {
            return instance;
        }
        var provider = serviceViaProvider(type);
        if (provider != null) {
            return provider;
        }
        if (type instanceof ParameterizedType parameterizedType) {
            var rawType = parameterizedType.getRawType();
            if (rawType.equals(Provider.class)) {
                var providedType = parameterizedType.getActualTypeArguments()[0];
                var actual = findServiceOfType(providedType);
                if (actual != null) {
                    return new ConstantProvider<>(actual);
                }
            }
        }
        return null;
    }

    private Object getServiceOfType(Type type) {
        var instance = findServiceOfType(type);
        if (instance != null) {
            return instance;
        }
        throw new RuntimeException("Cannot inject type " + type);
    }

    @Override
    public <T> T newInstance(Class<T> clazz, Object... argumentValues) {
        var creator = creators.computeIfAbsent(clazz, InjectedImplementation::implement);
        var targetParams = creator.constructor().handle().type().parameterArray();
        Object[] args = new Object[targetParams.length];
        int i = 0;
        for (var argValue : argumentValues) {
            if (i >= targetParams.length) {
                throw new IllegalArgumentException("Expected at most " + targetParams.length + " arguments, recieved " + argumentValues.length);
            }
            args[i] = argValue;
            if (!targetParams[i].isAssignableFrom(argValue.getClass())) {
                throw new IllegalArgumentException("Expected argument " + i + " to be of type " + targetParams[i] + ", recieved " + argValue.getClass());
            }
            i++;
        }
        while (i < targetParams.length) {
            args[i] = getServiceOfType(creator.injections().get(i));
            if (!targetParams[i].isAssignableFrom(args[i].getClass())) {
                throw new IllegalArgumentException("Expected argument " + i + " to be of type " + targetParams[i] + ", recieved " + args[i].getClass());
            }
            i++;
        }
        try {
            return clazz.cast(creator.constructor().invoke(args));
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public ObjectFactory newObjectFactory(Options options) {
        var factory = new ObjectFactoryImplementation(this.classLoader, this);
        factory.serviceProviders.putAll(options.providers);
        for (var serviceProvider : options.providers.entrySet()) {
            factory.services.put(TypeUtils.parameterize(Provider.class, serviceProvider.getKey()), serviceProvider.getValue());
        }
        factory.services.putAll(options.instances);
        return factory;
    }
}
