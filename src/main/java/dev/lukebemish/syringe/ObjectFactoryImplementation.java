package dev.lukebemish.syringe;

import org.jspecify.annotations.Nullable;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

class ObjectFactoryImplementation implements ObjectFactory {
    private static final Map<Class<?>, InjectedImplementation> creators = new ConcurrentHashMap<>();

    private final @Nullable ClassLoader classLoader;
    private final @Nullable ObjectFactoryImplementation parent;

    private final Map<Type, Object> instances = new HashMap<>();

    ObjectFactoryImplementation(ClassLoader classLoader, @Nullable ObjectFactoryImplementation parent) {
        this.classLoader = classLoader;
        this.parent = parent;
        instances.put(ObjectFactory.class, this);
    }

    void registerInstance(Type type, Object instance) {
        instances.put(type, instance);
    }

    private Object ofType(Type type) {
        var instance = instances.get(type);
        if (instance != null) {
            return instance;
        }
        if (parent != null) {
            return parent.ofType(type);
        }
        throw new RuntimeException("Cannot inject type " + type);
    }

    @Override
    public <T> T newInstance(Class<T> clazz, Object... argumentValues) {
        var creator = creators.computeIfAbsent(clazz, InjectedImplementation::implement);
        var targetParams = creator.constructor().type().parameterArray();
        List<Object> args = new ArrayList<>(creator.injections().size());
        int i = 0;
        for (var argValue : argumentValues) {
            args.add(argValue);
            if (i >= targetParams.length) {
                throw new IllegalArgumentException("Expected at most " + targetParams.length + " arguments, recieved " + argumentValues.length);
            }
            if (!targetParams[i].isAssignableFrom(argValue.getClass())) {
                throw new IllegalArgumentException("Expected argument " + i + " to be of type " + targetParams[i] + ", recieved " + argValue.getClass());
            }
            i++;
        }
        while (i < targetParams.length) {
            args.add(ofType(creator.injections().get(i)));
            i++;
        }
        try {
            return clazz.cast(creator.constructor().invokeWithArguments(args));
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }
}
