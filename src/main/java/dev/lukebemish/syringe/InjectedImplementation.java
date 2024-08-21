package dev.lukebemish.syringe;

import dev.lukebemish.syringe.annotations.Inject;
import net.neoforged.bus.api.SubscribeEvent;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.AccessFlag;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.SequencedSet;
import java.util.function.Function;

record InjectedImplementation(FastInvoker constructor, List<EvaluatedType> injectedServices, List<Instantiation> injectedInstances, int maxManualParameters) {
    record Instantiation(EvaluatedType type, Object[] args) {}

    private record InjectedMethod(String name, Class<?> erased, EvaluatedType specific, boolean isPublic) {}

    private static List<InjectedMethod> collectInjectedMethods(Class<?> clazz) {
        SequencedSet<InjectedMethod> methods = new LinkedHashSet<>();
        collectInjectedMethods(clazz, clazz, methods);
        return new ArrayList<>(methods);
    }

    private static void collectInjectedMethods(Class<?> root, Class<?> actual, SequencedSet<InjectedMethod> methods) {
        var superClazz = actual.getSuperclass();
        if (superClazz != null && (superClazz.getModifiers() & Modifier.ABSTRACT) != 0) {
            collectInjectedMethods(root, superClazz, methods);
        }
        for (var interfaceClazz : actual.getInterfaces()) {
            collectInjectedMethods(root, interfaceClazz, methods);
        }

        for (var method : actual.getDeclaredMethods()) {
            if (method.isAnnotationPresent(Inject.class)) {
                var modifier = method.getModifiers();
                if ((modifier & Modifier.FINAL) != 0) {
                    throw new RuntimeException("Injected getter must not be final");
                }
                if ((modifier & Modifier.PROTECTED) == 0 && (modifier & Modifier.PUBLIC) == 0) {
                    throw new RuntimeException("Injected getter must be protected or public");
                }
                if (method.getParameterTypes().length != 0) {
                    throw new RuntimeException("Injected getter must not have parameters");
                }
                var name = method.getName();
                var generic = method.getReturnType();
                if (generic.isPrimitive()) {
                    throw new RuntimeException("Cannot inject primitive types");
                }
                var specific = EvaluatedType.of(method.getGenericReturnType());
                methods.add(new InjectedMethod(name, generic, specific, (modifier & Modifier.PUBLIC) != 0));
            }
        }
    }

    static <T> Function<T, ?> wrap(Class<T> initialClazz) {
        var clazz = unimplement(initialClazz);

        var writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        var name = (Bootstrap.ATTACHMENT_TARGET_NAME+"$Wrapped").replace('.', '/');
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC, name, null, Type.getInternalName(Object.class), null);
        writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "delegate", clazz.descriptorString(), null, null).visitEnd();

        for (var method : collectMethodsToMirror(clazz)) {
            var methodImpl = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
                method.getName(),
                MethodType.methodType(method.getReturnType(), method.getParameterTypes()).descriptorString(),
                null, // does this matter?
                Arrays.stream(method.getExceptionTypes()).map(Type::getInternalName).toArray(String[]::new)
            );
            // We currently only keep `@SubscribeEvent`
            if (method.isAnnotationPresent(SubscribeEvent.class)) {
                methodImpl.visitAnnotation(SubscribeEvent.class.descriptorString(), true).visitEnd();
            }
            methodImpl.visitCode();
            methodImpl.visitVarInsn(Opcodes.ALOAD, 0);
            methodImpl.visitFieldInsn(Opcodes.GETFIELD, name, "delegate", clazz.descriptorString());
            int index = 1;
            for (int i = 0; i < method.getParameterCount(); i++) {
                var type = Type.getType(method.getParameterTypes()[i]);
                var opcode = type.getOpcode(Opcodes.ILOAD);
                methodImpl.visitVarInsn(opcode, index);
                index += type.getSize();
            }
            methodImpl.visitMethodInsn(Opcodes.INVOKEVIRTUAL, Type.getInternalName(clazz), method.getName(), MethodType.methodType(method.getReturnType(), method.getParameterTypes()).descriptorString(), false);
            var returnType = Type.getType(method.getReturnType());
            methodImpl.visitInsn(returnType.getOpcode(Opcodes.IRETURN));
            methodImpl.visitMaxs(0, 0);
            methodImpl.visitEnd();
        }

        var ctorImpl = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", MethodType.methodType(void.class, clazz).descriptorString(), null, null);
        ctorImpl.visitCode();
        ctorImpl.visitVarInsn(Opcodes.ALOAD, 0);
        ctorImpl.visitMethodInsn(Opcodes.INVOKESPECIAL, Type.getInternalName(Object.class), "<init>", "()V", false);

        ctorImpl.visitVarInsn(Opcodes.ALOAD, 0);
        ctorImpl.visitVarInsn(Opcodes.ALOAD, 1);
        ctorImpl.visitFieldInsn(Opcodes.PUTFIELD, name, "delegate", clazz.descriptorString());
        ctorImpl.visitInsn(Opcodes.RETURN);
        ctorImpl.visitMaxs(0, 0);
        ctorImpl.visitEnd();

        writer.visitEnd();
        var bytes = writer.toByteArray();
        try {
            var implementationLookup = Bootstrap.ATTACHMENT_TARGET.defineHiddenClass(bytes, false);
            var ctorHandle = implementationLookup.findConstructor(implementationLookup.lookupClass(), MethodType.methodType(void.class, clazz));
            return t -> {
                try {
                    return ctorHandle.invoke((T) t);
                } catch (Throwable e) {
                    throw new RuntimeException(e);
                }
            };
        } catch (IllegalAccessException | NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }

    private static Class<?> unimplement(Class<?> clazz) {
        if (!clazz.isHidden()) {
            return clazz;
        }
        try {
            var method = clazz.getMethod("$syringe_original_type");
            return (Class<?>) method.invoke(null);
        } catch (NoSuchMethodException e) {
            return clazz;
        } catch (InvocationTargetException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    static InjectedImplementation implement(Class<?> clazz) {
        if (clazz.isHidden()) {
            throw new IllegalArgumentException("Class to instantiate must not be hidden");
        }
        if ((clazz.getModifiers() & Modifier.PUBLIC) == 0) {
            throw new IllegalArgumentException("Class to instantiate must be public");
        }

        List<EvaluatedType> injectedTypes = new ArrayList<>();
        List<Class<?>> injectedClassTypes = new ArrayList<>();

        Constructor<?> targetCtor = null;
        Constructor<?> noArgCtor = null;
        for (var ctor : clazz.getDeclaredConstructors()) {
            if ((ctor.getModifiers() & Modifier.PROTECTED) == 0 && (ctor.getModifiers() & Modifier.PUBLIC) == 0) {
                continue;
            }
            if (ctor.getParameterCount() == 0) {
                noArgCtor = ctor;
            } else if (ctor.isAnnotationPresent(Inject.class)) {
                if (targetCtor != null) {
                    throw new RuntimeException("Class to instantiate must have at most one constructor annotated with @Inject");
                }
                targetCtor = ctor;
            }
        }
        if (targetCtor == null) {
            if (noArgCtor == null) {
                throw new RuntimeException("Class to instantiate must have a no-arg constructor or a constructor annotated with @Inject");
            }
            targetCtor = noArgCtor;
        }

        List<Class<?>> targetCtorArgs = new ArrayList<>();
        for (var param : targetCtor.getParameters()) {
            var type = param.getParameterizedType();
            var erased = param.getType();
            injectedTypes.add(EvaluatedType.of(type));
            injectedClassTypes.add(erased);
            targetCtorArgs.add(erased);
        }

        var methods = collectInjectedMethods(clazz);

        if (methods.isEmpty() && (targetCtor.getModifiers() & Modifier.PUBLIC) != 0 && (clazz.getModifiers() & Modifier.ABSTRACT) == 0) {
            try {
                var ctorHandle = MethodHandles.publicLookup().unreflectConstructor(targetCtor);
                return new InjectedImplementation(FastInvoker.create(ctorHandle), injectedTypes, List.of(), targetCtorArgs.size());
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        }

        if ((clazz.getModifiers() & Modifier.FINAL) != 0) {
            throw new RuntimeException("Class to instantiate with a protected constructor or abstract methods may not be final");
        }

        var writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        var name = (Bootstrap.ATTACHMENT_TARGET_NAME+"$"+clazz.getSimpleName()).replace('.', '/');
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC, name, null, Type.getInternalName(clazz), null);

        var implementationMethod = writer.visitMethod(
            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "$syringe_original_type",
            MethodType.methodType(Class.class).descriptorString(),
            null,
            null
        );
        implementationMethod.visitCode();
        implementationMethod.visitLdcInsn(Type.getType(clazz));
        implementationMethod.visitInsn(Opcodes.ARETURN);
        implementationMethod.visitMaxs(0, 0);
        implementationMethod.visitEnd();

        for (var method : methods) {
            var fieldName = "$syringe_injected_field$"+method.name;
            var field = writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, fieldName, Provider.class.descriptorString(), null, null);
            field.visitEnd();
            injectedTypes.add(new EvaluatedType(Provider.class, List.of(method.specific())));
            injectedClassTypes.add(Provider.class);
            var methodImpl = writer.visitMethod(
                (method.isPublic()? Opcodes.ACC_PUBLIC : Opcodes.ACC_PROTECTED) | Opcodes.ACC_FINAL,
                method.name,
                MethodType.methodType(method.erased()).descriptorString(),
                null,
                null
            );
            methodImpl.visitCode();
            methodImpl.visitVarInsn(Opcodes.ALOAD, 0);
            methodImpl.visitFieldInsn(Opcodes.GETFIELD, name, fieldName, Provider.class.descriptorString());
            methodImpl.visitMethodInsn(Opcodes.INVOKEINTERFACE, Type.getInternalName(Provider.class), "get", MethodType.methodType(Object.class).descriptorString(), true);
            methodImpl.visitTypeInsn(Opcodes.CHECKCAST, Type.getInternalName(method.erased()));
            methodImpl.visitInsn(Opcodes.ARETURN);
            methodImpl.visitMaxs(0, 0);
            methodImpl.visitEnd();
        }

        var ctorType = MethodType.methodType(void.class, injectedClassTypes);

        var ctor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", ctorType.descriptorString(), null, null);
        ctor.visitCode();
        int index = 1;
        for (var argType : targetCtorArgs) {
            var type = Type.getType(argType);
            index += type.getSize();
        }
        for (var method : methods) {
            ctor.visitVarInsn(Opcodes.ALOAD, 0);
            ctor.visitVarInsn(Opcodes.ALOAD, index);
            ctor.visitFieldInsn(Opcodes.PUTFIELD, name, "$syringe_injected_field$"+method.name, Provider.class.descriptorString());
            index++;
        }
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        index = 1;
        for (var argType : targetCtorArgs) {
            var type = Type.getType(argType);
            var opcode = type.getOpcode(Opcodes.ILOAD);
            ctor.visitVarInsn(opcode, index);
            index += type.getSize();
        }
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, Type.getInternalName(clazz), "<init>", MethodType.methodType(void.class, targetCtorArgs).descriptorString(), false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();

        writer.visitEnd();

        var bytes = writer.toByteArray();
        try {
            var implementationLookup = Bootstrap.ATTACHMENT_TARGET.defineHiddenClass(bytes, false);
            var ctorHandle = implementationLookup.findConstructor(implementationLookup.lookupClass(), ctorType);
            return new InjectedImplementation(FastInvoker.create(ctorHandle), injectedTypes, List.of(), targetCtorArgs.size());
        } catch (IllegalAccessException | NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }

    private static List<Method> collectMethodsToMirror(Class<?> clazz) {
        List<Method> methods = new ArrayList<>();
        for (var method : clazz.getMethods()) {
            // We only mirror public `@SubscribeEvent` methods
            if (!method.accessFlags().contains(AccessFlag.STATIC) && method.isAnnotationPresent(SubscribeEvent.class)) {
                methods.add(method);
            }
        }
        return methods;
    }
}
