package dev.lukebemish.syringe;

import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

class ObjectFactoryImplementation implements ObjectFactory {
    private static final Map<Class<?>, InjectedImplementation> creators = new ConcurrentHashMap<>();

    private final @Nullable ClassLoader classLoader;
    private final @Nullable ObjectFactoryImplementation parent;

    private final Map<EvaluatedType, Provider<?>> serviceProviders = new HashMap<>();
    private final Map<Class<?>, Provider<Instantiator<?>>> instantiators = new HashMap<>();

    private final Map<EvaluatedType, Configuration.ToCreate<Object>> servicesToPropogate = new HashMap<>();
    private final Map<Class<?>, Configuration.ToCreate<Instantiator<?>>> instantiatorsToPropogate = new HashMap<>();

    ObjectFactoryImplementation(@Nullable ClassLoader classLoader, @Nullable ObjectFactoryImplementation parent) {
        this.classLoader = classLoader;
        this.parent = parent;
        serviceProviders.put(EvaluatedType.of(ObjectFactory.class), new ConstantProvider<>(this));
    }

    private @Nullable Provider<?> serviceViaProvider(EvaluatedType type) {
        var provider = serviceProviders.get(type);
        if (provider != null) {
            return provider;
        }
        if (parent != null) {
            return parent.serviceViaProvider(type);
        }
        return null;
    }

    private @Nullable Object findServiceOfType(EvaluatedType type) {
        var provider = serviceViaProvider(type);
        if (provider != null) {
            return provider.get();
        }
        if (type.rawType().equals(Provider.class)) {
            var providedType = type.typeParameters().getFirst();
            var providerForActual = serviceViaProvider(providedType);
            if (providerForActual != null) {
                return providerForActual;
            }
            var actual = findServiceOfType(providedType);
            if (actual != null) {
                return new ConstantProvider<>(actual);
            }
        }
        return null;
    }

    private Object getServiceOfType(EvaluatedType type) {
        var instance = findServiceOfType(type);
        if (instance != null) {
            return instance;
        }
        throw new RuntimeException("Cannot inject type " + type);
    }

    private @Nullable Object tryInstantiate(EvaluatedType evaluatedType, Object[] args) {
        var instantiator = instantiators.get(evaluatedType.rawType());
        if (instantiator != null) {
            return instantiator.get().newInstance(evaluatedType.typeParameters(), args);
        }
        return parent != null ? parent.tryInstantiate(evaluatedType, args) : null;
    }

    private Object instantiate(EvaluatedType evaluatedType, Object[] args) {
        var instance = tryInstantiate(evaluatedType, args);
        if (instance != null) {
            return instance;
        }
        throw new RuntimeException("Cannot instantiate type " + evaluatedType);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T findService(Class<T> clazz) {
        return (T) getServiceOfType(EvaluatedType.of(clazz));
    }

    @Override
    public Object newInstance(EvaluatedType type, Object... args) {
        return instantiate(type, args);
    }

    @Override
    public <T> T newInstance(Class<T> clazz, Object... argumentValues) {
        var withInstantiator = tryInstantiate(EvaluatedType.of(clazz), argumentValues);
        if (withInstantiator != null) {
            return clazz.cast(withInstantiator);
        }

        var creator = creators.computeIfAbsent(clazz, InjectedImplementation::implement);
        var targetParams = creator.constructor().handle().type().parameterArray();
        Object[] args = new Object[targetParams.length];
        int i = 0;
        for (var argValue : argumentValues) {
            if (i >= creator.maxManualParameters()) {
                throw new IllegalArgumentException("Expected at most " + targetParams.length + " arguments, received " + argumentValues.length);
            }
            args[i] = argValue;
            i++;
        }
        var iterator = creator.injectedServices().iterator();
        for (int j = 0; j < i; j++) {
            iterator.next();
        }
        while (iterator.hasNext()) {
            var nextServiceType = iterator.next();
            args[i] = getServiceOfType(nextServiceType);
            i++;
        }
        for (InjectedImplementation.Instantiation instantiation : creator.injectedInstances()) {
            var instance = instantiate(new EvaluatedType(Provider.class, List.of(instantiation.type())), instantiation.args());
            args[i] = instance;
            i++;
        }
        try {
            return clazz.cast(creator.constructor().invoke(args));
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Override
    public ObjectFactoryImplementation newObjectFactory(Configuration configuration) {
        var factory = new ObjectFactoryImplementation(this.classLoader, this);
        factory.serviceProviders.putAll(configuration.providers);

        factory.servicesToPropogate.putAll(this.servicesToPropogate);
        factory.servicesToPropogate.putAll(configuration.toCreateServiceTypes);

        var servicesToCreate = new HashMap<>(factory.servicesToPropogate);
        servicesToCreate.putAll(configuration.toCreateServices);

        factory.instantiatorsToPropogate.putAll(this.instantiatorsToPropogate);
        factory.instantiatorsToPropogate.putAll(configuration.toCreateInstantiatorTypes);

        var instantiatorsToCreate = new HashMap<>(factory.instantiatorsToPropogate);
        instantiatorsToCreate.putAll(configuration.toCreateInstantiators);

        for (var entry : configuration.instantiators.entrySet()) {
            var provider = new ConstantProvider<Instantiator<?>>(entry.getValue());
            factory.instantiators.put(entry.getKey(), provider);
        }
        var toCreateInstantiators = new HashMap<Class<?>, DelayedProvider<Instantiator<?>>>();
        for (var entry : instantiatorsToCreate.entrySet()) {
            var provider = new DelayedProvider<Instantiator<?>>(new EvaluatedType(Instantiator.class, List.of(EvaluatedType.of(entry.getKey()))));
            toCreateInstantiators.put(entry.getKey(), provider);
        }
        factory.instantiators.putAll(toCreateInstantiators);
        var toCreateServices = new HashMap<EvaluatedType, DelayedProvider<?>>();
        for (var entry : servicesToCreate.entrySet()) {
            var provider = new DelayedProvider<>(entry.getKey());
            toCreateServices.put(entry.getKey(), provider);
        }
        factory.serviceProviders.putAll(toCreateServices);
        for (var entry : toCreateInstantiators.entrySet()) {
            var toCreate = instantiatorsToCreate.get(entry.getKey());
            var instance = factory.newInstance(toCreate.implementation(), toCreate.args());
            entry.getValue().set(instance);
        }
        for (var entry : toCreateServices.entrySet()) {
            var toCreate = servicesToCreate.get(entry.getKey());
            var instance = factory.newInstance(toCreate.implementation(), toCreate.args());
            ((DelayedProvider) entry.getValue()).set(instance);
        }
        return factory;
    }
}
