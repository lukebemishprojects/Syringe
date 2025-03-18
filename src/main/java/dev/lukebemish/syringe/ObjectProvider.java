package dev.lukebemish.syringe;

import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Qualifier;
import org.jspecify.annotations.Nullable;
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
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

sealed abstract class ObjectProvider<T> {
    abstract T create(Object[] args);
    T create() {
        return create(EMPTY);
    }
    Provider<T> createProvider(Object[] args) {
        return () -> create(args);
    }
    Provider<T> createProvider() {
        return createProvider(EMPTY);
    }
    Lazy<T> createLazy() {
        return new Memoize<>(this::create);
    }
    Lazy<T> createLazy(Object[] args) {
        return new Memoize<>(() -> create(args));
    }

    private static final class MemoizeObjectProvider<T> extends ObjectProvider<T> {
        private final Memoize<ObjectProvider<T>> memoize;

        public MemoizeObjectProvider(Supplier<ObjectProvider<T>> supplier) {
            this.memoize = new Memoize<>(supplier);
        }

        @Override
        T create(Object[] args) {
            return memoize.get().create(args);
        }

        @Override
        Provider<T> createProvider(Object[] args) {
            return memoize.get().createProvider(args);
        }

        @Override
        T create() {
            return memoize.get().create();
        }

        @Override
        Provider<T> createProvider() {
            return memoize.get().createProvider();
        }
    }

    private static final class SupplierObjectProvider<T> extends ObjectProvider<T> {
        private final AssistedFactory<T> supplier;

        public SupplierObjectProvider(AssistedFactory<T> supplier) {
            this.supplier = supplier;
        }

        @Override
        T create(Object[] args) {
            return supplier.create(args);
        }
    }

    private static final Object[] EMPTY = new Object[0];

    private static final class SingletonObjectProvider<T> extends ObjectProvider<T> {
        private final Memoize<T> memoize;

        public SingletonObjectProvider(AssistedFactory<T> supplier) {
            this.memoize = new Memoize<>(() -> supplier.create(EMPTY));
        }

        @Override
        T create(Object[] args) {
            if (args.length != 0) {
                throw new IllegalArgumentException("Scoped object provider does not take arguments");
            }
            return memoize.get();
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
        T create(Object[] args) {
            if (args.length != 0) {
                throw new IllegalArgumentException("Value object provider does not take arguments");
            }
            return value;
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
    private static final MethodHandle CREATE_LAZY;

    static {
        try {
            var lookup = MethodHandles.lookup();
            CREATE = lookup.findVirtual(ObjectProvider.class, "create", MethodType.methodType(Object.class));
            CREATE_PROVIDER = lookup.findVirtual(ObjectProvider.class, "createProvider", MethodType.methodType(Provider.class));
            CREATE_LAZY = lookup.findVirtual(ObjectProvider.class, "createLazy", MethodType.methodType(Lazy.class));
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    record Creator<T>(QualifiedType<?> target, Function<Instantiator, MethodHandle> handle, List<InjectedParameterType> parameters) {
        boolean isDynamic() {
            return parameters.stream().anyMatch(p -> p instanceof DynamicQualifierType);
        }

        Set<Class<? extends Annotation>> dynamicQualifiers() {
            return parameters.stream().filter(p -> p instanceof DynamicQualifierType).map(p -> ((DynamicQualifierType) p).type()).collect(Collectors.toSet());
        }

        ObjectProvider<T> bind(ObjectFactory factory, Instantiator instantiator, boolean singleton, Collection<Annotation> annotations) {
            return new MemoizeObjectProvider<>(() -> {
                List<@Nullable MethodHandle> parameterSuppliers = new ArrayList<>();
                Supplier<Map<Class<? extends Annotation>, Annotation>> lazyQualifierMap = new Memoize<>(() -> {
                    var map = new HashMap<Class<? extends Annotation>, Annotation>();
                    for (var annotation : annotations) {
                        if (map.put(annotation.annotationType(), annotation) != null) {
                            throw new IllegalArgumentException("Duplicate qualifier of type" + annotation.annotationType());
                        }
                    }
                    return map;
                });
                for (var parameter : parameters) {
                    switch (parameter) {
                        case ObjectProvider.AssistedParameterType ignored -> parameterSuppliers.add(null);
                        case ObjectProvider.SpecificQualifiedType<?> v -> {
                            var provider = factory.findOrMakeProvider(v.type());
                            switch (v.specific()) {
                                case PROVIDER -> parameterSuppliers.add(CREATE_PROVIDER.bindTo(provider));
                                case LAZY -> parameterSuppliers.add(CREATE_LAZY.bindTo(provider));
                                case PLAIN -> {
                                    var handle = CREATE.bindTo(provider);
                                    handle = handle.asType(MethodType.methodType(v.type().type()));
                                    parameterSuppliers.add(handle);
                                }
                            }
                        }
                        case ObjectProvider.DynamicQualifierType dynamicQualifierType -> {
                            var value = lazyQualifierMap.get().get(dynamicQualifierType.type());
                            if (value == null) {
                                throw new IllegalArgumentException("No qualifier of type " + dynamicQualifierType.type());
                            }
                            parameterSuppliers.add(MethodHandles.constant(dynamicQualifierType.type(), value));
                        }
                    }
                }

                // Now, a single combined handle from the Creator handle -- but we make sure to use the instantiation factory, so that components are instantiated with their parent factory but resolved with their own.
                var factoryHandle = handle().apply(instantiator);
                int pos = 0;
                for (var supplier : parameterSuppliers) {
                    if (supplier == null) {
                        pos++;
                        continue;
                    }
                    factoryHandle = MethodHandles.collectArguments(factoryHandle, pos, supplier);
                }

                if (pos != 0 && singleton) {
                    throw new IllegalArgumentException("Cannot have assisted parameters in a scoped value");
                }

                factoryHandle = checkSize(target(), factoryHandle);

                try {
                    AssistedFactory<T> supplier = implement(factoryHandle);
                    return singleton ? new SingletonObjectProvider<>(supplier) : new SupplierObjectProvider<>(supplier);
                } catch (Throwable e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    interface AssistedFactory<T> {
        T create(Object... assisted);
    }

    private static Object[] wrongLength(QualifiedType<?> constructing, int target, Object[] args) {
        if (args.length != target) {
            throw new IllegalArgumentException("Expected " + target + " arguments, but got " + args.length + " while constructing "+constructing);
        }
        return args;
    }

    private static final MethodHandle WRONG_LENGTH;

    static {
        try {
            WRONG_LENGTH = MethodHandles.lookup().findStatic(ObjectProvider.class, "wrongLength", MethodType.methodType(Object[].class, QualifiedType.class, int.class, Object[].class));
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static MethodHandle checkSize(QualifiedType<?> creating, MethodHandle handle) {
        var parameterCount = handle.type().parameterCount();
        handle = handle.asSpreader(Object[].class, parameterCount);
        handle = MethodHandles.filterArguments(handle, 0, MethodHandles.insertArguments(WRONG_LENGTH, 0, creating, parameterCount));
        return handle;
    }

    private static <T> AssistedFactory<T> implement(MethodHandle handle) {
        var cv = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        var name = Type.getInternalName(ObjectProvider.class) + "$$HandleSupplierImpl";
        cv.visit(Opcodes.V21, Opcodes.ACC_FINAL, name, null, Type.getInternalName(Object.class), new String[] {Type.getInternalName(AssistedFactory.class)});
        var mv = cv.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, Type.getInternalName(Object.class), "<init>", "()V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        mv = cv.visitMethod(Opcodes.ACC_PUBLIC, "create", "([Ljava/lang/Object;)Ljava/lang/Object;", null, null);
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
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, Type.getInternalName(MethodHandle.class), "invokeExact", "([Ljava/lang/Object;)Ljava/lang/Object;", false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cv.visitEnd();

        var bytes = cv.toByteArray();
        try {
            @SuppressWarnings("unchecked") var factory = (AssistedFactory<T>) MethodHandles.lookup().defineHiddenClassWithClassData(bytes, handle.asType(MethodType.methodType(Object.class, Object[].class)), true).lookupClass().getConstructor().newInstance();
            return factory;
        } catch (InvocationTargetException | InstantiationException | IllegalAccessException | NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }

    sealed interface InjectedParameterType {}

    enum SpecificType {
        PLAIN,
        PROVIDER,
        LAZY
    }

    record SpecificQualifiedType<T>(QualifiedType<T> type, SpecificType specific) implements InjectedParameterType {}
    record AssistedParameterType(Class<?> type) implements InjectedParameterType {}
    record DynamicQualifierType(Class<? extends Annotation> type) implements InjectedParameterType {}

    sealed interface Injection {
        Collection<InjectedParameterType> types();
    }

    sealed interface SetOnObjectInjection extends Injection {}

    record FieldInjection<T>(QualifiedType<T> type, Field field, SpecificType specific) implements SetOnObjectInjection {
        @Override
        public Collection<InjectedParameterType> types() {
            return List.of(new SpecificQualifiedType<>(type(), specific()));
        }
    }

    sealed interface CtorInjectionParameterType<T> {}

    record CtorInjectionParameter<T>(QualifiedType<T> type, SpecificType specific) implements CtorInjectionParameterType<T> {}
    record CtorAssistedParameter<T>(Class<?> type) implements CtorInjectionParameterType<T> {}
    record CtorDynamicQualifierParameter(Class<? extends Annotation> type) implements CtorInjectionParameterType<Annotation> {}

    sealed interface CtorLikeInjection extends Injection {
        List<CtorInjectionParameterType<?>> injections();

        @Override
        default Collection<InjectedParameterType> types() {
            var list = new ArrayList<InjectedParameterType>();
            for (var parameter : injections()) {
                list.add(switch (parameter) {
                    case ObjectProvider.CtorAssistedParameter<?> v -> new AssistedParameterType(v.type());
                    case ObjectProvider.CtorInjectionParameter<?> v -> new SpecificQualifiedType<>(v.type(), v.specific());
                    case ObjectProvider.CtorDynamicQualifierParameter v -> new DynamicQualifierType(v.type());
                });
            }
            return list;
        }
    }

    record CtorInjection(List<CtorInjectionParameterType<?>> injections, Constructor<?> constructor) implements CtorLikeInjection {}
    record ProvidesInjection(List<CtorInjectionParameterType<?>> injections, Method constructor) implements CtorLikeInjection {}

    record GetterInjection<T>(QualifiedType<T> type, Method method, SpecificType specific) implements Injection {
        @Override
        public Collection<InjectedParameterType> types() {
            return List.of(new SpecificQualifiedType<>(type(), specific()));
        }
    }

    record Binding<T>(QualifiedType<T> type, QualifiedType<?> implementation, Method method) {}

    record SetterInjectionParameter<T>(QualifiedType<T> type, SpecificType specific) {}

    record SetterInjection(List<SetterInjectionParameter<?>> injections, Method method) implements SetOnObjectInjection {
        @Override
        public Collection<InjectedParameterType> types() {
            var list = new ArrayList<InjectedParameterType>();
            for (var parameter : injections) {
                list.add(new SpecificQualifiedType<>(parameter.type(), parameter.specific()));
            }
            return list;
        }
    }

    static Set<Annotation> qualifiersOn(AnnotatedElement element) {
        var set = new HashSet<Annotation>();
        for (var annotation : element.getAnnotations()) {
            if (annotation.annotationType().isAnnotationPresent(Qualifier.class)) {
                set.add(annotation);
            }
        }
        return Set.copyOf(set);
    }

    private static boolean supportsQualifiers(Set<Annotation> qualifiers, Parameter[] parameters, AnnotatedElement element) {
        var dynamicQualifiers = new HashSet<Class<? extends Annotation>>();
        for (var parameter : parameters) {
            if (parameter.isAnnotationPresent(Dynamic.class) && Annotation.class.isAssignableFrom(parameter.getType())) {
                @SuppressWarnings("unchecked") Class<? extends Annotation> clazz = (Class<? extends Annotation>) parameter.getType();
                dynamicQualifiers.add(clazz);
            }
        }
        for (var qualifier : qualifiers) {
            if (qualifier.equals(element.getAnnotation(qualifier.annotationType()))) {
                continue;
            }
            if (!dynamicQualifiers.contains(qualifier.annotationType())) {
                return false;
            }
        }
        return true;
    }

    static <T> Creator<T> creatorForType(QualifiedType<T> type, Instantiator instantiator) {
        // We need to locate (a) @Inject fields, (b) @Inject constructors, (c) @Inject getter methods (that is abstract methods that return a type), and (d) @Inject setter methods (concrete methods that take types)

        // Cannot inject inner, local, anonymous, or hidden classes this way
        if (type.type().isLocalClass()) {
            throw new IllegalArgumentException("Cannot inject local class " + type);
        } else if (type.type().isAnonymousClass()) {
            throw new IllegalArgumentException("Cannot inject anonymous class " + type);
        } else if (type.type().isHidden()) {
            throw new IllegalArgumentException("Cannot inject hidden class " + type);
        } else if (type.type().isMemberClass() && !type.type().accessFlags().contains(AccessFlag.STATIC)) {
            throw new IllegalArgumentException("Cannot inject inner class " + type);
        }

        // We find a constructor. If any _one_ ctor has @Inject, we use that one -- otherwise, we use the no-arg constructor
        // More than one @Inject-ed constructor, or no matching constructor, is an exception
        Constructor<?> ctor = null;
        for (var constructor : type.type().getDeclaredConstructors()) {
            if (constructor.isAnnotationPresent(Inject.class)) {
                if (!supportsQualifiers(type.qualifiers(), constructor.getParameters(), constructor)) {
                    continue;
                }
                if (ctor != null) {
                    throw new IllegalArgumentException("Multiple @Inject constructors for " + type);
                }
                ctor = constructor;
            }
        }
        Method provider = null;
        for (var method : type.type().getDeclaredMethods()) {
            if (method.accessFlags().contains(AccessFlag.STATIC) && method.getReturnType().equals(type.type())) {
                if (method.isAnnotationPresent(Provides.class)) {
                    if (!supportsQualifiers(type.qualifiers(), method.getParameters(), method)) {
                        continue;
                    }
                    if (provider != null) {
                        throw new IllegalArgumentException("Multiple static @Provides methods for " + type);
                    }
                    provider = method;
                }
            }
        }
        if (ctor == null && provider == null) {
            try {
                ctor = type.type().getDeclaredConstructor();
                if (!supportsQualifiers(type.qualifiers(), ctor.getParameters(), ctor)) {
                    throw new NoSuchMethodException("No matching no-arg constructor");
                }
            } catch (NoSuchMethodException e) {
                throw new IllegalArgumentException("No no-arg constructor or @Inject-marked constructor for " + type);
            }
        } else if (provider != null && ctor != null) {
            throw new IllegalArgumentException("Cannot have both @Inject constructor and @Provides method for " + type);
        }

        var ctorParameters = new ArrayList<CtorInjectionParameterType<?>>();

        var initParameterArray = provider == null ? ctor.getParameters() : provider.getParameters();
        for (Parameter parameter : initParameterArray) {
            var parameterType = parameter.getType();
            if (parameter.isAnnotationPresent(Assisted.class)) {
                ctorParameters.add(new CtorAssistedParameter<>(parameterType));
                continue;
            } else if (parameter.isAnnotationPresent(Dynamic.class)) {
                if (!Annotation.class.isAssignableFrom(parameterType) || !parameterType.isAnnotationPresent(Qualifier.class)) {
                    throw new IllegalArgumentException("@Dynamic parameter must be a @Qualifier annotation type");
                }
                @SuppressWarnings("unchecked") Class<? extends Annotation> clazz = (Class<? extends Annotation>) parameterType;
                ctorParameters.add(new CtorDynamicQualifierParameter(clazz));
                continue;
            }
            specificForType(parameterType, parameter.getParameterizedType(), parameter, (specific, clazzType) -> {
                ctorParameters.add(new CtorInjectionParameter<>(new QualifiedType<>(clazzType, qualifiersOn(parameter)), specific));
            });
        }
        final var ctorInjection = provider == null ? new CtorInjection(ctorParameters, ctor) : new ProvidesInjection(ctorParameters, provider);

        final List<SetOnObjectInjection> setterInjections = new ArrayList<>();
        final List<GetterInjection<?>> getterInjections = new ArrayList<>();
        final List<Binding<?>> bindings = new ArrayList<>();

        boolean isComponent = type.type().isAnnotationPresent(Component.class);

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

                    var hasInject = method.isAnnotationPresent(Inject.class);
                    var hasBinding = method.isAnnotationPresent(Binds.class);

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
                            specificForType(returnType, method.getGenericReturnType(), method, (specific, clazzType) -> {
                                getterInjections.add(new GetterInjection<>(new QualifiedType<>(clazzType, qualifiersOn(method)), method, specific));
                            });
                        } else {
                            // This is a normal @Inject method
                            List<SetterInjectionParameter<?>> parameters = new ArrayList<>();
                            for (int i = 0; i < method.getParameters().length; i++) {
                                var parameter = method.getParameters()[i];
                                var parameterType = parameter.getType();
                                specificForType(parameterType, parameter.getParameterizedType(), parameter, (specific, clazzType) -> {
                                    parameters.add(new SetterInjectionParameter<>(new QualifiedType<>(clazzType, qualifiersOn(parameter)), specific));
                                });
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
                        specificForType(fieldType, field.getGenericType(), field, (specific, clazzType) -> {
                            setterInjections.add(new FieldInjection<>(new QualifiedType<>(clazzType, qualifiersOn(field)), field, specific));
                        });
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

        Collections.reverse(getterInjections);
        Collections.reverse(setterInjections);

        var handleTypes = new ArrayList<>(ctorInjection.types());

        // Vc... -> T
        MethodHandle handle;
        if (!bindings.isEmpty() || !getterInjections.isEmpty()) {
            for (var getter : getterInjections) {
                handleTypes.add(new SpecificQualifiedType<>(getter.type(), switch (getter.specific()) {
                    case PROVIDER -> SpecificType.PROVIDER;
                    case LAZY, PLAIN -> SpecificType.LAZY;
                }));
            }

            // We need the class to be public
            if (!type.type().accessFlags().contains(AccessFlag.PUBLIC)) {
                throw new IllegalArgumentException("Class "+type+" must be public to have abstract @Inject methods or @Binds methods");
            }

            if (ctor == null) {
                throw new IllegalArgumentException("Class "+type+" cannot have abstract @Inject methods or @Binds methods with a @Provides static factory method");
            }

            if (!ctor.accessFlags().contains(AccessFlag.PUBLIC) && !ctor.accessFlags().contains(AccessFlag.PROTECTED)) {
                throw new IllegalArgumentException("Injectable constructor for class " + type + " must be public or protected to have abstract @Inject methods or @Binds methods");
            }

            handle = implementAbstract(type.type(), getterInjections, bindings, ((CtorInjection) ctorInjection).constructor(), instantiator);
        } else {
            try {
                handle = switch (ctorInjection) {
                    case CtorInjection ctorInjectionImpl -> privateIn(ctorInjectionImpl.constructor().getDeclaringClass(), instantiator).unreflectConstructor(ctorInjectionImpl.constructor());
                    case ProvidesInjection providesInjection -> privateIn(providesInjection.constructor().getDeclaringClass(), instantiator).unreflect(providesInjection.constructor());
                };
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        }

        for (var injection : setterInjections) {
            switch (injection) {
                case FieldInjection<?> fieldInjection -> {
                    var field = fieldInjection.field();

                    try {
                        // T, Vnew -> T
                        var fieldHandle = identityManySetter(privateIn(field.getDeclaringClass(), instantiator).unreflectSetter(field));
                        var adaptedFieldHandle = fieldHandle.asType(fieldHandle.type().changeReturnType(type.type()).changeParameterType(0, type.type()));

                        // V... , Vnew -> T
                        handle = MethodHandles.collectArguments(adaptedFieldHandle, 0, handle);
                    } catch (IllegalAccessException e) {
                        throw new RuntimeException(e);
                    }

                    handleTypes.addAll(fieldInjection.types());
                }
                case SetterInjection setterInjection -> {
                    var setter = setterInjection.method();

                    try {
                        // T, Vnew... -> T
                        var methodHandle = identityManySetter(MethodHandles.dropReturn(privateIn(setter.getDeclaringClass(), instantiator).unreflect(setter)));
                        var adaptedMethodHandle = methodHandle.asType(methodHandle.type().changeReturnType(type.type()).changeParameterType(0, type.type()));

                        // V... , Vnew... -> T
                        handle = MethodHandles.collectArguments(adaptedMethodHandle, 0, handle);
                    } catch (IllegalAccessException e) {
                        throw new RuntimeException(e);
                    }

                    handleTypes.addAll(setterInjection.types());
                }
            }
        }

        final var finalHandle = handle;

        var actualQualifiers = new HashSet<Annotation>();
        for (var qualifier : (provider == null ? ctor : provider).getAnnotations()) {
            if (qualifier.annotationType().isAnnotationPresent(Qualifier.class)) {
                actualQualifiers.add(qualifier);
            }
        }

        return new Creator<>(new QualifiedType<>(type.type(), Set.copyOf(actualQualifiers)), i -> finalHandle, handleTypes);
    }

    static void specificForType(Class<?> parameterType, java.lang.reflect.Type fullType, Object context, BiConsumer<SpecificType, Class<?>> consumer) {
        if (parameterType == Provider.class) {
            if (fullType instanceof ParameterizedType parameterizedType && parameterizedType.getActualTypeArguments()[0] instanceof Class<?> providerType) {
                consumer.accept(SpecificType.PROVIDER, providerType);
            } else {
                throw new IllegalArgumentException("Cannot understand type of Provider injection" + context);
            }
        } else if (parameterType == Lazy.class) {
            if (fullType instanceof ParameterizedType parameterizedType && parameterizedType.getActualTypeArguments()[0] instanceof Class<?> providerType) {
                consumer.accept(SpecificType.LAZY, providerType);
            } else {
                throw new IllegalArgumentException("Cannot understand type of Lazy injection " + context);
            }
        } else {
            consumer.accept(SpecificType.PLAIN, parameterType);
        }
    }

    private static MethodHandle implementAbstract(Class<?> type, List<GetterInjection<?>> getterInjections, List<Binding<?>> bindings, Constructor<?> superCtor, Instantiator instantiator) {
        // Complicatedness ensues. First, we need to get somewhere we can place the implementation class, that can read
        // the class it is implementing (and presumably the various types it holds as well). This would be great if we
        // could do what proxy classes do and make use of JavaLangAccess. However, this type of stuff is restricted by
        // JPMS for a reason. So instead, we capture a lookup in the object factory.

        var lookup = instantiator.implementAbstract(type, getterInjections, bindings, superCtor);
        try {
            return lookup.unreflectConstructor(lookup.lookupClass().getDeclaredConstructors()[0]);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    static MethodHandles.Lookup privateIn(Class<?> clazz, Instantiator instantiator) {
        MethodHandles.Lookup lookup = instantiator.lookup();
        try {
            try {
                lookup = instantiator.privateIn(clazz);
            } catch (IllegalAccessException ignored) {
                lookup = MethodHandles.privateLookupIn(clazz, MethodHandles.lookup());
            }
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
