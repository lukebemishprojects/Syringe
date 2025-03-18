package dev.lukebemish.syringe.neoforge.attachment;

import com.google.common.collect.MapMaker;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.AccessFlag;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;

public final class SuperclassAllowingEventBus implements IEventBus {
    private static MethodHandles.Lookup getGameLayerLookup() {
        return GameComponent.gameLayerInstantiator().lookup();
    }

    private static final Map<Class<?>, Function<?, ?>> WRAPPERS = new ConcurrentHashMap<>();

    private final IEventBus delegate;
    private final Map<Object, Object> registeredWrappers = new MapMaker().weakKeys().makeMap();

    public SuperclassAllowingEventBus(IEventBus delegate) {
        this.delegate = delegate;
    }

    private static Function<?, ?> makeWrapper(Class<?> classToWrap) {
        var lookup = getGameLayerLookup();

        record MethodRef(String name, MethodType type) {}
        record PackageMethodRef(String name, MethodType type, String packageName) {}

        Set<PackageMethodRef> packageMethodsVisited = new HashSet<>();
        Set<MethodRef> methodsVisited = new HashSet<>();

        List<MethodHandle> listenerHandles = new ArrayList<>();
        Consumer<Class<?>> classVisitor = new Consumer<>() {
            @Override
            public void accept(Class<?> clazz) {
                for (var method : clazz.getDeclaredMethods()) {
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

                    if (method.isAnnotationPresent(SubscribeEvent.class)) {
                        // We need to collect this method
                        if (method.getParameterTypes().length != 1)
                        {
                            throw new IllegalArgumentException(
                                "Method " + method + " has @SubscribeEvent annotation. " +
                                    "It has " + method.getParameterTypes().length + " arguments, " +
                                    "but event handler methods require a single argument only."
                            );
                        }
                        try {
                            var finalLookup = lookup;
                            try {
                                finalLookup = MethodHandles.privateLookupIn(method.getDeclaringClass(), lookup);
                            } catch (IllegalAccessException ignored) {
                                // Use non-private lookup
                            }
                            var handle = finalLookup.unreflect(method);
                            handle = handle.asType(handle.type().changeParameterType(0, Object.class).changeReturnType(void.class));
                            listenerHandles.add(handle);
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
        classVisitor.accept(classToWrap);

        // We have a list of method handles. Now, we implement the relevant type
        var cv = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        var name = getGameLayerLookup().lookupClass().getName().replace('.', '/') + "$$SyringeEventBusWrapper";
        cv.visit(Opcodes.V21, Opcodes.ACC_FINAL | Opcodes.ACC_PUBLIC, name, null, Type.getInternalName(Object.class), new String[0]);

        // Store object to field
        cv.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "object", Type.getDescriptor(Object.class), null, null);

        // Generate a constructor
        var mv = cv.visitMethod(Opcodes.ACC_PUBLIC, "<init>", MethodType.methodType(void.class, Object.class).descriptorString(), null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, Type.getInternalName(Object.class), "<init>", "()V", false);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitFieldInsn(Opcodes.PUTFIELD, name, "object", Type.getDescriptor(Object.class));
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        // For every handler, generate a subscriber
        for (int i = 0; i < listenerHandles.size(); i++) {
            var handle = listenerHandles.get(i);
            var desc = MethodType.methodType(void.class, handle.type().parameterType(1)).descriptorString();
            mv = cv.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, "$listener"+i, desc, null, null);

            mv.visitAnnotation(Type.getDescriptor(SubscribeEvent.class), true).visitEnd();

            mv.visitCode();
            mv.visitLdcInsn(classDataAt(i));
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitFieldInsn(Opcodes.GETFIELD, name, "object", Type.getDescriptor(Object.class));
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, Type.getInternalName(MethodHandle.class), "invokeExact", handle.type().descriptorString(), false);
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }

        cv.visitEnd();

        var bytes = cv.toByteArray();
        try {
            var hiddenLookup = lookup.defineHiddenClassWithClassData(bytes, listenerHandles, false);
            var constructor = hiddenLookup.findConstructor(hiddenLookup.lookupClass(), MethodType.methodType(void.class, Object.class));
            return implementFunction(constructor);
        } catch (IllegalAccessException | NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }

    private static Function<?, ?> implementFunction(MethodHandle handle) {
        var cv = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        var name = Type.getInternalName(SuperclassAllowingEventBus.class) + "$$FunctionWrapper";
        cv.visit(Opcodes.V21, Opcodes.ACC_FINAL, name, null, Type.getInternalName(Object.class), new String[] {Type.getInternalName(Function.class)});
        var mv = cv.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, Type.getInternalName(Object.class), "<init>", "()V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        mv = cv.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, "apply", "(Ljava/lang/Object;)Ljava/lang/Object;", null, null);
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
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, Type.getInternalName(MethodHandle.class), "invokeExact", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cv.visitEnd();

        var bytes = cv.toByteArray();
        try {
            return (Function<?, ?>) MethodHandles.lookup().defineHiddenClassWithClassData(bytes, handle.asType(MethodType.methodType(Object.class, Object.class)), true).lookupClass().getConstructor().newInstance();
        } catch (InvocationTargetException | InstantiationException | IllegalAccessException | NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }

    private static @NotNull ConstantDynamic classDataAt(int index) {
        return new ConstantDynamic(
            "_",
            Type.getDescriptor(MethodHandle.class),
            new Handle(
                Opcodes.H_INVOKESTATIC,
                Type.getInternalName(MethodHandles.class),
                "classDataAt",
                MethodType.methodType(Object.class, MethodHandles.Lookup.class, String.class, Class.class, int.class).descriptorString(),
                false
            ),
            index
        );
    }

    @SuppressWarnings("unchecked")
    @Override
    public void register(Object target) {
        if (!(target instanceof Class<?>)) {
            var wrapper = (Function<Object, Object>) WRAPPERS.computeIfAbsent(target.getClass(), SuperclassAllowingEventBus::makeWrapper);
            target = wrapper.apply(target);
        }
        delegate.register(target);
    }

    @Override
    public <T extends Event> void addListener(Consumer<T> consumer) {
        delegate.addListener(consumer);
    }

    @Override
    public <T extends Event> void addListener(Class<T> eventType, Consumer<T> consumer) {
        delegate.addListener(eventType, consumer);
    }

    @Override
    public <T extends Event> void addListener(EventPriority priority, Consumer<T> consumer) {
        delegate.addListener(priority, consumer);
    }

    @Override
    public <T extends Event> void addListener(EventPriority priority, Class<T> eventType, Consumer<T> consumer) {
        delegate.addListener(priority, eventType, consumer);
    }

    @Override
    public <T extends Event> void addListener(EventPriority priority, boolean receiveCanceled, Consumer<T> consumer) {
        delegate.addListener(priority, receiveCanceled, consumer);
    }

    @Override
    public <T extends Event> void addListener(EventPriority priority, boolean receiveCanceled, Class<T> eventType, Consumer<T> consumer) {
        delegate.addListener(priority, receiveCanceled, eventType, consumer);
    }

    @Override
    public <T extends Event> void addListener(boolean receiveCanceled, Consumer<T> consumer) {
        delegate.addListener(receiveCanceled, consumer);
    }

    @Override
    public <T extends Event> void addListener(boolean receiveCanceled, Class<T> eventType, Consumer<T> consumer) {
        delegate.addListener(receiveCanceled, eventType, consumer);
    }

    @Override
    public void unregister(Object object) {
        var wrapper = registeredWrappers.remove(object);
        if (wrapper != null) {
            delegate.unregister(wrapper);
        }
        delegate.unregister(object);
    }

    @Override
    public <T extends Event> T post(T event) {
        return delegate.post(event);
    }

    @Override
    public <T extends Event> T post(EventPriority phase, T event) {
        return delegate.post(phase, event);
    }

    @Override
    public void start() {
        delegate.start();
    }
}
