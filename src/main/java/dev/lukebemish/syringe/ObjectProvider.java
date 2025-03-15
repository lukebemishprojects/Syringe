package dev.lukebemish.syringe;

import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Qualifier;
import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.lang.annotation.Annotation;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.AccessFlag;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

sealed abstract class ObjectProvider<T> {
    abstract T create();
    Provider<T> createProvider() {
        return this::create;
    }

    private static final class MemoizeObjectProvider<T> extends ObjectProvider<T> {
        private final Memoize<ObjectProvider<T>> memoize;

        public MemoizeObjectProvider(Supplier<ObjectProvider<T>> supplier) {
            this.memoize = new Memoize<>(supplier);
        }

        @Override
        T create() {
            return memoize.get().create();
        }
    }

    private static final class SupplierObjectProvider<T> extends ObjectProvider<T> {
        private final Supplier<T> supplier;

        public SupplierObjectProvider(Supplier<T> supplier) {
            this.supplier = supplier;
        }

        @Override
        T create() {
            return supplier.get();
        }
    }

    private static final class SingletonObjectProvider<T> extends ObjectProvider<T> {
        private final Memoize<T> memoize;

        public SingletonObjectProvider(Supplier<T> supplier) {
            this.memoize = new Memoize<>(supplier);
        }

        @Override
        T create() {
            return memoize.get();
        }
    }

    private static final class ValueObjectProvider<T> extends ObjectProvider<T> {
        private final T value;

        public ValueObjectProvider(T value) {
            this.value = value;
        }

        @Override
        T create() {
            return value;
        }
    }

    static <T> ObjectProvider<T> of(T value) {
        return new ValueObjectProvider<>(value);
    }

    private static final MethodHandle CREATE;
    private static final MethodHandle CREATE_PROVIDER;

    static {
        try {
            var lookup = MethodHandles.lookup();
            CREATE = lookup.findVirtual(ObjectProvider.class, "create", MethodType.methodType(Object.class));
            CREATE_PROVIDER = lookup.findVirtual(ObjectProvider.class, "createProvider", MethodType.methodType(Provider.class));
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    record Creator<T>(Function<ObjectFactory, MethodHandle> handle, List<ProviderQualifiedType<?>> parameters) {
        ObjectProvider<T> bind(ObjectFactory factory, boolean singleton) {
            return new MemoizeObjectProvider<>(() -> {
                List<MethodHandle> parameterSuppliers = new ArrayList<>();
                for (var parameter : parameters) {
                    var provider = factory.objectProvider(parameter.type());
                    if (parameter.provider()) {
                        parameterSuppliers.add(CREATE_PROVIDER.bindTo(provider));
                    } else {
                        var handle = CREATE.bindTo(provider);
                        handle = handle.asType(MethodType.methodType(parameter.type().type()));
                        parameterSuppliers.add(handle);
                    }
                }

                // Now, a single combined handle from the Creator handle
                var supplierHandle = handle().apply(factory);
                for (var supplier : parameterSuppliers) {
                    supplierHandle = MethodHandles.collectArguments(supplierHandle, 0, supplier);
                }

                try {
                    Supplier<T> supplier = implement(supplierHandle);
                    return singleton ? new SingletonObjectProvider<>(supplier) : new SupplierObjectProvider<>(supplier);
                } catch (Throwable e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    private static <T> Supplier<T> implement(MethodHandle handle) {
        var cv = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        var name = Type.getInternalName(ObjectProvider.class) + "$$HandleSupplierImpl";
        cv.visit(Opcodes.V21, Opcodes.ACC_FINAL, name, null, Type.getInternalName(Object.class), new String[] {Type.getInternalName(Supplier.class)});
        var mv = cv.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, Type.getInternalName(Object.class), "<init>", "()V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        mv = cv.visitMethod(Opcodes.ACC_PUBLIC, "get", "()Ljava/lang/Object;", null, null);
        var handleConstant = new ConstantDynamic(
            "_",
            Type.getDescriptor(MethodHandle.class),
            new Handle(
                Opcodes.H_INVOKESTATIC,
                Type.getInternalName(MethodHandles.class),
                "classData",
                MethodType.methodType(Object.class, MethodHandles.Lookup.class, String.class, Class.class).descriptorString(),
                false
            )
        );
        mv.visitLdcInsn(handleConstant);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, Type.getInternalName(MethodHandle.class), "invokeExact", "()Ljava/lang/Object;", false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cv.visitEnd();

        var bytes = cv.toByteArray();
        try {
            @SuppressWarnings("unchecked") var supplier = (Supplier<T>) MethodHandles.lookup().defineHiddenClassWithClassData(bytes, handle.asType(MethodType.methodType(Object.class)), true).lookupClass().getConstructor().newInstance();
            return supplier;
        } catch (InvocationTargetException | InstantiationException | IllegalAccessException | NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }

    record ProviderQualifiedType<T>(QualifiedType<T> type, boolean provider) {}

    interface Injection {
        Collection<ProviderQualifiedType<?>> types();
    }

    sealed interface SetOnObjectInjection extends Injection {}

    record FieldInjection<T>(QualifiedType<T> type, Field field, boolean provider) implements SetOnObjectInjection {
        @Override
        public Collection<ProviderQualifiedType<?>> types() {
            return List.of(new ProviderQualifiedType<>(type(), provider()));
        }
    }

    record CtorInjectionParameter<T>(QualifiedType<T> type, boolean provider) {}

    sealed interface CtorLikeInjection extends Injection {
        List<CtorInjectionParameter<?>> injections();

        @Override
        default Collection<ProviderQualifiedType<?>> types() {
            var list = new ArrayList<ProviderQualifiedType<?>>();
            for (var parameter : injections()) {
                list.add(new ProviderQualifiedType<>(parameter.type(), parameter.provider()));
            }
            return list;
        }
    }

    record CtorInjection(List<CtorInjectionParameter<?>> injections, Constructor<?> constructor) implements CtorLikeInjection {}
    record ProvidesInjection(List<CtorInjectionParameter<?>> injections, Method constructor) implements CtorLikeInjection {}

    record GetterInjection<T>(QualifiedType<T> type, Method method, boolean provider) implements Injection {
        @Override
        public Collection<ProviderQualifiedType<?>> types() {
            return List.of(new ProviderQualifiedType<>(type(), provider()));
        }
    }

    record Binding<T>(QualifiedType<T> type, QualifiedType<?> implementation, Method method) {}

    record SetterInjectionParameter<T>(QualifiedType<T> type, boolean provider) {}

    record SetterInjection(List<SetterInjectionParameter<?>> injections, Method method) implements SetOnObjectInjection {
        @Override
        public Collection<ProviderQualifiedType<?>> types() {
            var list = new ArrayList<ProviderQualifiedType<?>>();
            for (var parameter : injections) {
                list.add(new ProviderQualifiedType<>(parameter.type(), parameter.provider()));
            }
            return list;
        }
    }

    static Set<Annotation> qualifiersOn(AnnotatedElement element) {
        var set = new HashSet<Annotation>();
        for (var annotation : element.getAnnotations()) {
            if (annotation.annotationType().getAnnotation(Qualifier.class) != null) {
                set.add(annotation);
            }
        }
        return Set.copyOf(set);
    }

    @SuppressWarnings("unchecked")
    static <T> ObjectProvider<T> forType(Class<T> type, ObjectFactory factory, boolean singleton) {
        return ((Creator<T>) creators.get(type)).bind(factory, singleton);
    }

    private static final ClassValue<Creator<?>> creators = new ClassValue<>() {
        @Override
        protected Creator<?> computeValue(@NotNull Class<?> type) {
            // We need to locate (a) @Inject fields, (b) @Inject constructors, (c) @Inject getter methods (that is abstract methods that return a type), and (d) @Inject setter methods (concrete methods that take types)

            // Cannot inject inner, local, anonymous, or hidden classes this way
            if (type.isLocalClass()) {
                throw new IllegalArgumentException("Cannot inject local class " + type);
            } else if (type.isAnonymousClass()) {
                throw new IllegalArgumentException("Cannot inject anonymous class " + type);
            } else if (type.isHidden()) {
                throw new IllegalArgumentException("Cannot inject hidden class " + type);
            } else if (type.isMemberClass() && !type.accessFlags().contains(AccessFlag.STATIC)) {
                throw new IllegalArgumentException("Cannot inject inner class " + type);
            }

            // We find a constructor. If any _one_ ctor has @Inject, we use that one -- otherwise, we use the no-arg constructor
            // More than one @Inject-ed constructor, or no matching constructor, is an exception
            Constructor<?> ctor = null;
            for (var constructor : type.getDeclaredConstructors()) {
                if (constructor.isAnnotationPresent(Inject.class)) {
                    if (ctor != null) {
                        throw new IllegalArgumentException("Multiple @Inject constructors for " + type);
                    }
                    ctor = constructor;
                }
            }
            Method provider = null;
            for (var method : type.getDeclaredMethods()) {
                if (method.accessFlags().contains(AccessFlag.STATIC) && method.getReturnType().equals(type)) {
                    if (method.isAnnotationPresent(Provides.class)) {
                        if (provider != null) {
                            throw new IllegalArgumentException("Multiple static @Provides methods for " + type);
                        }
                        provider = method;
                    }
                }
            }
            if (ctor == null && provider == null) {
                try {
                    ctor = type.getDeclaredConstructor();
                } catch (NoSuchMethodException e) {
                    throw new IllegalArgumentException("No no-arg constructor or @Inject-marked constructor for " + type);
                }
            } else if (provider != null && ctor != null) {
                throw new IllegalArgumentException("Cannot have both @Inject constructor and @Provides method for " + type);
            }

            var ctorParameters = new ArrayList<CtorInjectionParameter<?>>();

            var initParameterArray = provider == null ? ctor.getParameters() : provider.getParameters();
            for (int i = 0; i < initParameterArray.length; i++) {
                var parameter = initParameterArray[i];
                var parameterType = parameter.getType();
                if (parameterType == Provider.class) {
                    if (parameter.getParameterizedType() instanceof ParameterizedType parameterizedType && parameterizedType.getActualTypeArguments()[0] instanceof Class<?> providerType) {
                        ctorParameters.add(new CtorInjectionParameter<>(new QualifiedType<>(providerType, qualifiersOn(parameter)), true));
                    } else {
                        throw new IllegalArgumentException("Cannot understand type of Provider parameter " + parameter);
                    }
                } else {
                    ctorParameters.add(new CtorInjectionParameter<>(new QualifiedType<>(parameterType, qualifiersOn(parameter)), false));
                }
            }
            final var ctorInjection = provider == null ? new CtorInjection(ctorParameters, ctor) : new ProvidesInjection(ctorParameters, provider);

            final List<SetOnObjectInjection> setterInjections = new ArrayList<>();
            final List<GetterInjection<?>> getterInjections = new ArrayList<>();
            final List<Binding<?>> bindings = new ArrayList<>();

            boolean isComponent = type.isAnnotationPresent(Component.class);

            // We note that overridden methods are not considered
            Set<PackageMethodRef> packageMethodsVisited = new HashSet<>();
            Set<MethodRef> methodsVisited = new HashSet<>();

            Consumer<Class<?>> typeConsumer = new Consumer<>() {
                @Override
                public void accept(Class<?> clazz) {
                    for (int j = clazz.getDeclaredMethods().length - 1; j >= 0; j--) {
                        var method = clazz.getDeclaredMethods()[j];
                        if (method.accessFlags().contains(AccessFlag.STATIC)) {
                            continue;
                        }

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

                        var hasInject = method.getAnnotation(Inject.class) != null;
                        var hasBinding = method.getAnnotation(Binds.class) != null;

                        if (hasInject && hasBinding) {
                            throw new IllegalArgumentException("Method "+method+" has both @Inject and @Binds");
                        } else if (hasBinding && !method.accessFlags().contains(AccessFlag.ABSTRACT)) {
                            throw new IllegalArgumentException("Method "+method+" is not abstract, but methods with @Binds must be");
                        } else if (hasBinding && !isComponent) {
                            throw new IllegalArgumentException("Method "+method+" has @Binds but is not in a @Component");
                        } if (hasInject) {
                            if (method.accessFlags().contains(AccessFlag.ABSTRACT)) {
                                if (!method.accessFlags().contains(AccessFlag.PROTECTED) && !method.accessFlags().contains(AccessFlag.PUBLIC)) {
                                    throw new IllegalArgumentException("Method "+method+" is not public or protected, but abstract methods with @Inject must be");
                                }
                                // This is an abstract getter
                                var returnType = method.getReturnType();
                                if (returnType == Provider.class) {
                                    if (method.getGenericReturnType() instanceof ParameterizedType parameterizedType && parameterizedType.getActualTypeArguments()[0] instanceof Class<?> providerType) {
                                        getterInjections.add(new GetterInjection<>(new QualifiedType<>(providerType, qualifiersOn(method)), method, true));
                                    } else {
                                        throw new IllegalArgumentException("Cannot understand type of Provider return type " + method);
                                    }
                                } else {
                                    getterInjections.add(new GetterInjection<>(new QualifiedType<>(returnType, qualifiersOn(method)), method, false));
                                }
                            } else {
                                // This is a normal @Inject method
                                List<SetterInjectionParameter<?>> parameters = new ArrayList<>();
                                for (int i = 0; i < method.getParameters().length; i++) {
                                    var parameter = method.getParameters()[i];
                                    var parameterType = parameter.getType();
                                    if (parameterType == Provider.class) {
                                        if (parameter.getParameterizedType() instanceof ParameterizedType parameterizedType && parameterizedType.getActualTypeArguments()[0] instanceof Class<?> providerType) {
                                            parameters.add(new SetterInjectionParameter<>(new QualifiedType<>(providerType, qualifiersOn(parameter)), true));
                                        } else {
                                            throw new IllegalArgumentException("Cannot understand type of Provider parameter " + parameter);
                                        }
                                    } else {
                                        parameters.add(new SetterInjectionParameter<>(new QualifiedType<>(parameterType, qualifiersOn(parameter)), false));
                                    }
                                }
                                setterInjections.add(new SetterInjection(parameters, method));
                            }
                        } else if (hasBinding) {
                            if (!method.accessFlags().contains(AccessFlag.PROTECTED) && !method.accessFlags().contains(AccessFlag.PUBLIC)) {
                                throw new IllegalArgumentException("Method "+method+" is not public or protected, but methods with @Binds must be");
                            }
                            // This is an binding
                            var returnType = method.getReturnType();
                            if (method.getParameters().length != 1) {
                                throw new IllegalArgumentException("Method "+method+" has @Binds but does not have exactly one parameter");
                            }
                            var parameter = method.getParameters()[0];
                            var parameterType = parameter.getType();
                            if (!returnType.isAssignableFrom(parameterType)) {
                                throw new IllegalArgumentException("Method "+method+" attempts to @Binds "+parameterType+" to incompatible type "+returnType);
                            }
                            bindings.add(new Binding<>(new QualifiedType<>(returnType, qualifiersOn(method)), new QualifiedType<>(parameterType, qualifiersOn(parameter)), method));
                        }
                    }

                    for (int j = clazz.getDeclaredFields().length - 1; j >= 0; j--) {
                        var field = clazz.getDeclaredFields()[j];
                        if (field.accessFlags().contains(AccessFlag.STATIC) || field.accessFlags().contains(AccessFlag.FINAL)) {
                            // The TCK seems to expect STATIC at least to not error but just be ignored
                            continue;
                        }
                        if (field.isAnnotationPresent(Inject.class)) {
                            var fieldType = field.getType();
                            if (fieldType == Provider.class) {
                                if (field.getGenericType() instanceof ParameterizedType parameterizedType && parameterizedType.getActualTypeArguments()[0] instanceof Class<?> providerType) {
                                    setterInjections.add(new FieldInjection<>(new QualifiedType<>(providerType, qualifiersOn(field)), field, true));
                                } else {
                                    throw new IllegalArgumentException("Cannot understand type of Provider field " + field);
                                }
                            } else {
                                setterInjections.add(new FieldInjection<>(new QualifiedType<>(fieldType, qualifiersOn(field)), field, false));
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

            Collections.reverse(getterInjections);
            Collections.reverse(setterInjections);

            try {
                var handleTypes = new ArrayList<>(ctorInjection.types());

                // Vc... -> T
                Function<ObjectFactory, MethodHandle> handle;
                if (!bindings.isEmpty() || !getterInjections.isEmpty()) {
                    for (var getter : getterInjections) {
                        handleTypes.add(new ProviderQualifiedType<>(getter.type(), getter.provider()));
                    }

                    // We need the class to be public
                    if (!type.accessFlags().contains(AccessFlag.PUBLIC)) {
                        throw new IllegalArgumentException("Class "+type+" must be public to have abstract @Inject methods or @Binds methods");
                    }

                    if (ctor == null) {
                        throw new IllegalArgumentException("Class "+type+" cannot have abstract @Inject methods or @Binds methods with a @Provides static factory method");
                    }

                    if (!ctor.accessFlags().contains(AccessFlag.PUBLIC) && !ctor.accessFlags().contains(AccessFlag.PROTECTED)) {
                        throw new IllegalArgumentException("Injectable constructor for class " + type + " must be public or protected to have abstract @Inject methods or @Binds methods");
                    }

                    handle = implementAbstract(type, getterInjections, bindings, ((CtorInjection) ctorInjection).constructor());
                } else {
                    handle = factory -> {
                        try {
                            return switch (ctorInjection) {
                                case CtorInjection ctorInjectionImpl -> forConstructor(ctorInjectionImpl.constructor()).unreflectConstructor(ctorInjectionImpl.constructor());
                                case ProvidesInjection providesInjection -> forMethod(providesInjection.constructor()).unreflect(providesInjection.constructor());
                            };
                        } catch (IllegalAccessException e) {
                            throw new RuntimeException(e);
                        }
                    };
                }

                for (var injection : setterInjections) {
                    switch (injection) {
                        case FieldInjection<?> fieldInjection -> {
                            var field = fieldInjection.field();
                            // T, Vnew -> T
                            var fieldHandle = identityManySetter(forField(field).unreflectSetter(field));
                            var adaptedFieldHandle = fieldHandle.asType(fieldHandle.type().changeReturnType(type).changeParameterType(0, type));

                            // V... , Vnew -> T
                            handle = handle.andThen(h -> MethodHandles.collectArguments(adaptedFieldHandle, 0, h));
                            handleTypes.addAll(fieldInjection.types());
                        }
                        case SetterInjection setterInjection -> {
                            var setter = setterInjection.method();
                            // T, Vnew... -> T
                            var methodHandle = identityManySetter(MethodHandles.dropReturn(forMethod(setter).unreflect(setter)));
                            var adaptedMethodHandle = methodHandle.asType(methodHandle.type().changeReturnType(type).changeParameterType(0, type));

                            // V... , Vnew... -> T
                            handle = handle.andThen(h -> MethodHandles.collectArguments(adaptedMethodHandle, 0, h));
                            handleTypes.addAll(setterInjection.types());
                        }
                    }
                }

                return new Creator<>(handle, handleTypes);
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        }
    };

    private static Function<ObjectFactory, MethodHandle> implementAbstract(Class<?> type, List<GetterInjection<?>> getterInjections, List<Binding<?>> bindings, Constructor<?> superCtor) {
        // Complicatedness ensues. First, we need to get somewhere we can place the implementation class, that can read
        // the class it is implementing (and presumably the various types it holds as well). This would be great if we
        // could do what proxy classes do and make use of JavaLangAccess. However, this type of stuff is restricted by
        // JPMS for a reason. So instead, we capture a lookup in the object factory.

        return factory -> {
            var instantiator = factory.instance(Instantiator.class);
            var lookup = instantiator.implementAbstract(type, getterInjections, bindings, superCtor);
            try {
                return lookup.unreflectConstructor(lookup.lookupClass().getDeclaredConstructors()[0]);
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        };
    }

    private static MethodHandles.Lookup forConstructor(Constructor<?> constructor) {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        try {
            lookup = MethodHandles.privateLookupIn(constructor.getDeclaringClass(), lookup);
        } catch (IllegalAccessException ignored) {
            // We just won't have private access -- if that causes other issues, so be it.
        }
        return lookup;
    }

    private static MethodHandles.Lookup forMethod(Method method) {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        try {
            lookup = MethodHandles.privateLookupIn(method.getDeclaringClass(), lookup);
        } catch (IllegalAccessException ignored) {
            // We just won't have private access -- if that causes other issues, so be it.
        }
        return lookup;
    }

    private static MethodHandles.Lookup forField(Field field) {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        try {
            lookup = MethodHandles.privateLookupIn(field.getDeclaringClass(), lookup);
        } catch (IllegalAccessException ignored) {
            // We just won't have private access -- if that causes other issues, so be it.
        }
        return lookup;
    }

    // Takes a setter of form (T, V) -> void and makes a handle of the form (T, V) -> T
    private static MethodHandle identityManySetter(MethodHandle setter) {
        // T, T, V... -> T
        var combined = MethodHandles.collectArguments(MethodHandles.identity(setter.type().parameterType(0)), 1, setter);
        // T, V... -> T
        return MethodHandles.foldArguments(combined, MethodHandles.identity(setter.type().parameterType(0)));
    }
}
