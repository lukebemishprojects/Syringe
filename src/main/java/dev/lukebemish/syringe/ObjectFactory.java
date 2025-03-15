package dev.lukebemish.syringe;

import jakarta.inject.Provider;
import jakarta.inject.Scope;
import jakarta.inject.Singleton;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.AccessFlag;
import java.lang.reflect.ParameterizedType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

public final class ObjectFactory {
    private final Set<Class<? extends Annotation>> scope;
    private final Map<QualifiedType<?>, ObjectProvider<?>> implementations = new HashMap<>();
    private final @Nullable ObjectFactory parent;

    private ObjectFactory(Set<Class<? extends Annotation>> scope, @Nullable ObjectFactory parent) {
        this.scope = scope;
        this.parent = parent;
    }

    {
        implementations.put(new QualifiedType<>(ObjectFactory.class, Set.of()), ObjectProvider.of(this));
    }

    @SuppressWarnings("unchecked")
    <T> ObjectProvider<T> objectProvider(QualifiedType<T> type) {
        var impl = implementations.get(type);
        if (impl != null) {
            return (ObjectProvider<T>) impl;
        }

        var isScoped = false;
        var scopedAnnotations = Arrays.stream(type.type().getAnnotations())
            .filter(annotation -> annotation.annotationType().getAnnotation(Scope.class) != null)
            .toList();
        if (scopedAnnotations.size() == 1) {
            var annotation = scopedAnnotations.getFirst();
            if (scope.contains(annotation.annotationType())) {
                isScoped = true;
            } else {
                // This factory cannot handle this scope -- try the parent.
                if (parent == null) {
                    throw new IllegalArgumentException("Class "+type.type()+" has unsupported scope "+annotation.annotationType());
                }
                return parent.objectProvider(type);
            }
        } else if (!scopedAnnotations.isEmpty()) {
            throw new IllegalArgumentException("Class "+type.type()+" has multiple scopes "+scopedAnnotations);
        }

        if (!type.qualifiers().isEmpty()) {
            throw new IllegalArgumentException("Class "+type.type()+" with qualifiers "+type.qualifiers()+", but no matching provider could be found");
        }

        var component = type.type().getAnnotation(Component.class);
        if (component != null) {
            var scopes = Set.of(component.scopes());
            var child = new ObjectFactory(scopes, this);
            Set<PackageMethodRef> packageMethodsVisited = new HashSet<>();
            Set<MethodRef> methodsVisited = new HashSet<>();

            Consumer<Class<?>> typeConsumer = new Consumer<>() {
                @Override
                public void accept(Class<?> clazz) {
                    // We look through all the various methods of the class to find ones with @Provides or @Binds.
                    // These should all turn into providers. Notably -- if they have scopes, those _must_ be compatible with this factory.
                    for (var method : clazz.getDeclaredMethods()) {
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

                        var hasProvides = method.getAnnotation(Provides.class) != null;
                        var hasBinds = method.getAnnotation(Binds.class) != null;
                        if (hasProvides && method.accessFlags().contains(AccessFlag.ABSTRACT)) {
                            throw new IllegalArgumentException("Method "+method+" is abstract, but methods with @Provides must not be");
                        } else if (hasBinds && !method.accessFlags().contains(AccessFlag.ABSTRACT)) {
                            throw new IllegalArgumentException("Method "+method+" is not abstract, but methods with @Binds must be");
                        } else if (hasProvides && hasBinds) {
                            throw new IllegalArgumentException("Method "+method+" has both @Provides and @Binds");
                        }
                        if (hasProvides || hasBinds) {
                            boolean isScoped = false;
                            for (var annotation : method.getAnnotations()) {
                                if (annotation.annotationType().getAnnotation(Scope.class) != null) {
                                    if (!scope.contains(annotation.annotationType())) {
                                        throw new IllegalArgumentException("Method "+method+" has scope "+annotation.annotationType()+" which is not compatible with "+scopes);
                                    } else if (isScoped) {
                                        throw new IllegalArgumentException("Method "+method+" has multiple scopes");
                                    }
                                    isScoped = true;
                                }
                            }

                            if (method.accessFlags().contains(AccessFlag.STATIC) && (hasBinds || (hasProvides && method.getReturnType().equals(clazz)))) {
                                throw new IllegalArgumentException("Method "+method+" is static, but methods with @Provides or @Binds must not be unless they are static factory methods with @Provides");
                            }

                            MethodHandles.Lookup lookup = MethodHandles.lookup();
                            try {
                                lookup = MethodHandles.privateLookupIn(clazz, lookup);
                            } catch (IllegalAccessException ignored) {
                                // We just won't have private access -- if that causes other issues, so be it.
                            }

                            var qualifiers = ObjectProvider.qualifiersOn(method);

                            try {
                                var handle = lookup.unreflect(method);
                                var parameters = new ArrayList<ObjectProvider.ProviderQualifiedType<?>>();
                                parameters.add(new ObjectProvider.ProviderQualifiedType<>(type, false));
                                for (var parameter : method.getParameters()) {
                                    var parameterType = parameter.getType();
                                    var parameterQualifiers = ObjectProvider.qualifiersOn(parameter);
                                    var isProvider = false;
                                    if (parameterType.equals(Provider.class)) {
                                        if (parameter.getParameterizedType() instanceof ParameterizedType parameterizedType && parameterizedType.getActualTypeArguments()[0] instanceof Class<?> providerType) {
                                            isProvider = true;
                                            parameterType = providerType;
                                        } else {
                                            throw new IllegalArgumentException("Cannot understand type of Provider parameter " + parameter);
                                        }
                                    }
                                    parameters.add(new ObjectProvider.ProviderQualifiedType<>(new QualifiedType<>(parameterType, parameterQualifiers), isProvider));
                                }
                                var creator = new ObjectProvider.Creator<>(factory -> handle, parameters);
                                child.implementations.put(new QualifiedType<>(method.getReturnType(), qualifiers), creator.bind(child, ObjectFactory.this, isScoped));
                            } catch (IllegalAccessException e) {
                                throw new RuntimeException(e);
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
            typeConsumer.accept(type.type());
            var provider = ObjectProvider.forType(type.type(), child, true);
            if (isScoped) {
                implementations.put(type, provider);
            }
            return provider;
        }

        var provider = ObjectProvider.forType(type.type(), this, isScoped);
        implementations.put(type, provider);
        return provider;
    }

    public <T> T instance(Class<T> clazz) {
        var provider = objectProvider(new QualifiedType<>(clazz, Set.of()));
        return provider.create();
    }

    public <T> Provider<T> provider(Class<T> clazz) {
        var provider = objectProvider(new QualifiedType<>(clazz, Set.of()));
        return provider.createProvider();
    }

    public static ObjectFactory create() {
        return new ObjectFactory(Set.of(Singleton.class), null);
    }
}
