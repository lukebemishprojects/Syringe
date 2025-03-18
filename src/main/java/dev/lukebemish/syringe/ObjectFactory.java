package dev.lukebemish.syringe;

import jakarta.inject.Provider;
import jakarta.inject.Qualifier;
import jakarta.inject.Scope;
import jakarta.inject.Singleton;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.AccessFlag;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public final class ObjectFactory {
    private final Set<Class<? extends Annotation>> scope;
    private final Map<QualifiedType<?>, ObjectProvider<?>> scoped = new ConcurrentHashMap<>();
    private final Map<QualifiedType<?>, ObjectProvider.Creator<?>> creators = new ConcurrentHashMap<>();
    private final Map<QualifiedType<?>, ObjectProvider<?>> boundCreators = new ConcurrentHashMap<>();

    private final Map<Class<?>, DynamicQualifierInfo<?>> dynamicQualifiers = new ConcurrentHashMap<>();
    private final Map<Class<?>, DynamicQualifierInfo<?>> scopedDynamicQualifiers = new ConcurrentHashMap<>();

    final @Nullable ObjectFactory parent;

    private ObjectFactory(Set<Class<? extends Annotation>> scope, @Nullable ObjectFactory parent, Instantiator instantiator) {
        this.scope = scope;
        this.parent = parent;

        scoped.put(new QualifiedType<>(ObjectFactory.class, Set.of()), ObjectProvider.of(this));

        scoped.put(new QualifiedType<>(Instantiator.class, Set.of()), ObjectProvider.of(instantiator));
    }

    @SuppressWarnings("unchecked")
    <T> @Nullable ObjectProvider<T> findScoped(QualifiedType<T> type) {
        var impl = scoped.get(type);
        if (impl != null) {
            return (ObjectProvider<T>) impl;
        }
        var dynamic = scopedDynamicQualifiers.get(type.type());
        if (dynamic != null && dynamic.matches(type)) {
            var value = (ObjectProvider<T>) dynamic.creator().bind(this, this.instantiator(), true, type.qualifiers());
            scoped.put(type, value);
            return value;
        }
        return parent == null ? null : parent.findScoped(type);
    }

    @SuppressWarnings("unchecked")
    <T> ObjectProvider.@Nullable Creator<T> findCreator(QualifiedType<T> type) {
        var impl = creators.get(type);
        if (impl != null) {
            return (ObjectProvider.Creator<T>) impl;
        }
        return parent == null ? null : parent.findCreator(type);
    }

    @SuppressWarnings("unchecked")
    <T> @Nullable DynamicQualifierInfo<T> findDynamicCreator(QualifiedType<T> type) {
        var impl = dynamicQualifiers.get(type.type());
        if (impl != null && impl.matches(type)) {
            return (DynamicQualifierInfo<T>) impl;
        }
        return parent == null ? null : parent.findDynamicCreator(type);
    }

    @SuppressWarnings("unchecked")
    <T> ObjectProvider<T> findOrMakeProvider(QualifiedType<T> type) {
        var boundCreator = boundCreators.get(type);
        if (boundCreator != null) {
            return (ObjectProvider<T>) boundCreator;
        }

        var creator = findCreator(type);
        if (creator != null) {
            var provider = creator.bind(this, this.instantiator(), false, List.of());
            boundCreators.put(type, provider);
            return provider;
        }

        var dynamicCreator = findDynamicCreator(type);
        if (dynamicCreator != null) {
            var provider = dynamicCreator.creator().bind(this, this.instantiator(), false, type.qualifiers());
            boundCreators.put(type, provider);
            return provider;
        }

        var scoped = findScoped(type);
        if (scoped != null) {
            return scoped;
        }

        ObjectFactory topMostFactory = findScopedFactory(type.type());
        if (topMostFactory != null) {
            // Scoped
            return topMostFactory.createScoped(type);
        }

        var newCreator = ObjectProvider.creatorForType(type, instantiator());
        if (newCreator.isDynamic()) {
            var dynamicQualifierInfo = new DynamicQualifierInfo<>(newCreator, newCreator.target().qualifiers(), newCreator.dynamicQualifiers(), type.type());
            dynamicQualifiers.put(type.type(), dynamicQualifierInfo);
            var bound = newCreator.bind(this, instantiator(), false, type.qualifiers());
            boundCreators.put(type, bound);
            return bound;
        } else {
            creators.put(type, newCreator);
            var bound = newCreator.bind(this, instantiator(), false, List.of());
            boundCreators.put(type, bound);
            return bound;
        }
    }

    private @Nullable ObjectFactory findScopedFactory(AnnotatedElement element) {
        // some special-casing for things used in the ObjectFactory itself
        if (element == Instantiator.class || (element instanceof Method method && method.getReturnType() == Instantiator.class)) {
            return this;
        }

        var allAnnotations = Arrays.stream(element.getAnnotations())
            .<Class<? extends Annotation>>map(Annotation::annotationType)
            .collect(Collectors.toCollection(HashSet::new));
        var creatingFactory = this;
        ObjectFactory topMostFactory = null;
        while (creatingFactory != null) {
            if (allAnnotations.removeAll(creatingFactory.scope)) {
                // If we removed anything, then this scope is necessary
                topMostFactory = creatingFactory;
            }

            creatingFactory = creatingFactory.parent;
        }
        var nonMatchingScopedAnnotations = allAnnotations.stream()
            .filter(annotation -> annotation.isAnnotationPresent(Scope.class))
            .toList();
        if (!nonMatchingScopedAnnotations.isEmpty()) {
            throw new IllegalArgumentException(element+" has unsupported scopes "+nonMatchingScopedAnnotations);
        }
        return topMostFactory;
    }

    private Instantiator instantiator() {
        return findOrMakeProvider(new QualifiedType<>(Instantiator.class, Set.of())).create();
    }

    @SuppressWarnings("unchecked")
    private <T> ObjectProvider<T> createScoped(QualifiedType<T> type) {
        // We search _only_ this particular factory -- as if it is scoped, it only makes sense to look for here.
        var dynamicCreator = scopedDynamicQualifiers.get(type.type());
        if (dynamicCreator != null) {
            var provider = dynamicCreator.creator().bind(this, this.instantiator(), true, type.qualifiers());
            scoped.put(type, provider);
            return (ObjectProvider<T>) provider;
        }

        var newCreator = ObjectProvider.creatorForType(type, findOrMakeProvider(new QualifiedType<>(Instantiator.class, Set.of())).create());
        if (newCreator.isDynamic()) {
            scopedDynamicQualifiers.put(type.type(), new DynamicQualifierInfo<>(newCreator, newCreator.target().qualifiers(), newCreator.dynamicQualifiers(), type.type()));
            var provider = newCreator.bind(this, instantiator(), true, type.qualifiers());
            scoped.put(type, provider);
            return provider;
        } else {
            var newProvider = newCreator.bind(this, instantiator(), true, List.of());
            scoped.put(type, newProvider);
            return newProvider;
        }
    }

    public ObjectFactory within(Object component) {
        var type = component.getClass();
        var scopes = new HashSet<Class<? extends Annotation>>();
        var child = new ObjectFactory(scopes, this, this.instantiator());
        Set<PackageMethodRef> packageMethodsVisited = new HashSet<>();
        Set<MethodRef> methodsVisited = new HashSet<>();

        Consumer<Class<?>> typeConsumer = new Consumer<>() {
            @Override
            public void accept(Class<?> clazz) {
                if (!clazz.isHidden()) {
                    // Skip hidden classes

                    if (clazz.isAnnotationPresent(Component.class)) {
                        var component = clazz.getAnnotation(Component.class);
                        scopes.addAll(Set.of(component.scopes()));
                    }

                    // We look through all the various methods of the class to find ones with @Provides or @Binds.
                    // These should all turn into providers. Notably -- if they have scopes, those _must_ be compatible with this factory.
                    for (var method : clazz.getDeclaredMethods()) {
                        if (!method.accessFlags().contains(AccessFlag.STATIC)) {
                            if (method.accessFlags().contains(AccessFlag.PUBLIC) || method.accessFlags().contains(AccessFlag.PROTECTED)) {
                                var packageMethodRef = new PackageMethodRef(method.getName(), MethodType.methodType(method.getReturnType(), method.getParameterTypes()), clazz.getPackageName());
                                var methodRef = new MethodRef(method.getName(), MethodType.methodType(method.getReturnType(), method.getParameterTypes()));
                                packageMethodsVisited.add(packageMethodRef);
                                if (!methodsVisited.add(methodRef)) {
                                    // This was overridden in a subclass
                                    continue;
                                }
                            } else if (!method.accessFlags().contains(AccessFlag.PRIVATE)) {
                                var packageMethodRef = new PackageMethodRef(method.getName(), MethodType.methodType(method.getReturnType(), method.getParameterTypes()), clazz.getPackageName());
                                if (!packageMethodsVisited.add(packageMethodRef)) {
                                    // This was overridden in a subclass
                                    continue;
                                }
                            }
                        }

                        var hasProvides = method.isAnnotationPresent(Provides.class);
                        var hasBinds = method.isAnnotationPresent(Binds.class);
                        if (hasProvides && method.accessFlags().contains(AccessFlag.ABSTRACT)) {
                            throw new IllegalArgumentException("Method " + method + " is abstract, but methods with @Provides must not be");
                        } else if (hasBinds && !method.accessFlags().contains(AccessFlag.ABSTRACT)) {
                            throw new IllegalArgumentException("Method " + method + " is not abstract, but methods with @Binds must be");
                        } else if (hasProvides && hasBinds) {
                            throw new IllegalArgumentException("Method " + method + " has both @Provides and @Binds");
                        }
                        if (hasProvides || hasBinds) {
                            if (method.accessFlags().contains(AccessFlag.STATIC)) {
                                if (method.getReturnType().equals(clazz)) {
                                    continue;
                                }
                                throw new IllegalArgumentException("Method " + method + " is static, but methods with @Provides or @Binds must not be unless they are static factory methods with @Provides");
                            }

                            var scopedFactory = child.findScopedFactory(method);
                            if (scopedFactory != null && scopedFactory != child) {
                                throw new IllegalArgumentException("Component @Provides method " + method + " has scope that is not compatible with the component");
                            }

                            boolean isScoped = scopedFactory != null;

                            MethodHandles.Lookup lookup = ObjectProvider.privateIn(clazz, instantiator());

                            var qualifiers = ObjectProvider.qualifiersOn(method);

                            if (method.getReturnType().equals(ObjectFactory.class) && qualifiers.isEmpty()) {
                                throw new IllegalArgumentException("Components may not provide ObjectFactory without qualifiers");
                            }

                            try {
                                var handle = lookup.unreflect(method).bindTo(component);
                                var parameters = new ArrayList<ObjectProvider.InjectedParameterType>();
                                for (var parameter : method.getParameters()) {
                                    if (parameter.isAnnotationPresent(Assisted.class)) {
                                        parameters.add(new ObjectProvider.AssistedParameterType(parameter.getType()));
                                        continue;
                                    } else if (parameter.isAnnotationPresent(Dynamic.class)) {
                                        if (!Annotation.class.isAssignableFrom(parameter.getType()) || !parameter.getType().isAnnotationPresent(Qualifier.class)) {
                                            throw new IllegalArgumentException("@Dynamic parameter must be a @Qualifier annotation type");
                                        }
                                        @SuppressWarnings("unchecked") Class<? extends Annotation> annotationType = (Class<? extends Annotation>) parameter.getType();
                                        parameters.add(new ObjectProvider.DynamicQualifierType(annotationType));
                                        continue;
                                    }
                                    var parameterType = new Class<?>[]{parameter.getType()};
                                    var parameterQualifiers = ObjectProvider.qualifiersOn(parameter);
                                    var specific = new ObjectProvider.SpecificType[1];
                                    ObjectProvider.specificForType(parameterType[0], parameter.getParameterizedType(), parameterQualifiers, (s, c) -> {
                                        specific[0] = s;
                                        parameterType[0] = c;
                                    });
                                    parameters.add(new ObjectProvider.SpecificQualifiedType<>(new QualifiedType<>(parameterType[0], parameterQualifiers), specific[0]));
                                }
                                var qualifiedType = new QualifiedType<>(method.getReturnType(), qualifiers);
                                var creator = new ObjectProvider.Creator<>(qualifiedType, instantiator -> handle, parameters);

                                if (creator.isDynamic()) {
                                    @SuppressWarnings({"rawtypes", "unchecked"}) var dynamicQualifierInfo = new DynamicQualifierInfo<>((ObjectProvider.Creator) creator, creator.target().qualifiers(), creator.dynamicQualifiers(), method.getReturnType());
                                    if (isScoped) {
                                        child.scopedDynamicQualifiers.put(method.getReturnType(), dynamicQualifierInfo);
                                    } else {
                                        child.dynamicQualifiers.put(method.getReturnType(), dynamicQualifierInfo);
                                    }
                                } else {
                                    var provider = creator.bind(child, instantiator(), isScoped, List.of());

                                    if (isScoped) {
                                        child.scoped.put(qualifiedType, provider);
                                    } else {
                                        child.creators.put(qualifiedType, creator);
                                        child.boundCreators.put(qualifiedType, provider);
                                    }
                                }
                            } catch (IllegalAccessException e) {
                                throw new RuntimeException(e);
                            }
                        }
                    }
                }

                var superType = clazz.getSuperclass();
                if (superType != null && !superType.equals(Object.class)) {
                    accept(superType);
                }
                for (var interfaceType : clazz.getInterfaces()) {
                    accept(interfaceType);
                }
            }
        };
        typeConsumer.accept(type);
        return child;
    }

    private final Map<Class<?>, ObjectProvider<?>> directClassBindings = Collections.synchronizedMap(new IdentityHashMap<>());
    @SuppressWarnings("unchecked")
    private <T> ObjectProvider<T> direct(Class<T> clazz) {
        return (ObjectProvider<T>) directClassBindings.computeIfAbsent(clazz, c -> findOrMakeProvider(new QualifiedType<>(clazz, Set.of())));
    }

    private <T> ObjectProvider<T> qualified(Class<T> clazz, Collection<Annotation> qualifiers) {
        return findOrMakeProvider(new QualifiedType<>(clazz, Set.copyOf(qualifiers)));
    }

    public <T> T instance(Collection<Annotation> qualifiers, Class<T> clazz) {
        return qualified(clazz, qualifiers).create();
    }

    public <T> T instance(Class<T> clazz) {
        return direct(clazz).create();
    }

    public <T> T provider(Collection<Annotation> qualifiers, Class<T> clazz) {
        return qualified(clazz, qualifiers).createProvider().get();
    }

    public <T> Provider<T> provider(Class<T> clazz) {
        return direct(clazz).createProvider();
    }

    public <T> T lazy(Collection<Annotation> qualifiers, Class<T> clazz) {
        return qualified(clazz, qualifiers).createLazy().get();
    }

    public <T> Lazy<T> lazy(Class<T> clazz) {
        return direct(clazz).createLazy();
    }

    public <T> T instance(Collection<Annotation> qualifiers, Class<T> clazz, Object... args) {
        return qualified(clazz, qualifiers).create(args);
    }

    public <T> T instance(Class<T> clazz, Object... args) {
        return direct(clazz).create(args);
    }

    public <T> T provider(Collection<Annotation> qualifiers, Class<T> clazz, Object... args) {
        return qualified(clazz, qualifiers).createProvider(args).get();
    }

    public <T> Provider<T> provider(Class<T> clazz, Object... args) {
        return direct(clazz).createProvider(args);
    }

    public <T> T lazy(Collection<Annotation> qualifiers, Class<T> clazz, Object... args) {
        return qualified(clazz, qualifiers).createLazy(args).get();
    }

    public <T> Lazy<T> lazy(Class<T> clazz, Object... args) {
        return direct(clazz).createLazy(args);
    }

    public static ObjectFactory create() {
        return create(Instantiator.builder().build());
    }

    public static ObjectFactory create(Instantiator instantiator) {
        return new ObjectFactory(Set.of(Singleton.class), null, instantiator);
    }
}
