package dev.lukebemish.syringe;

import dev.lukebemish.syringe.annotations.Inject;
import net.neoforged.fml.loading.FMLLoader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.SequencedSet;

record InjectedImplementation(MethodHandle constructor, List<Type> injections) {
    private static final String ATTACHMENT_MODULE = "dev.lukebemish.syringe.attachment";
    private static final String ATTACHMENT_TARGET_NAME = "dev.lukebemish.syringe.attachment.AttachmentTarget";

    private static final MethodHandles.Lookup ATTACHMENT_TARGET;

    static final ObjectFactoryImplementation BOOTSTRAP;
    static final ObjectFactoryImplementation ROOT;

    static {
        var layer = FMLLoader.getGameLayer();
        try {
            var targetClass = layer.findLoader(ATTACHMENT_MODULE).loadClass(ATTACHMENT_TARGET_NAME);
            var lookupMethod = MethodHandles.publicLookup().findStatic(targetClass, "lookup", MethodType.methodType(MethodHandles.Lookup.class));
            ATTACHMENT_TARGET = (MethodHandles.Lookup) lookupMethod.invoke();

            BOOTSTRAP = new ObjectFactoryImplementation(null, null);
            ROOT = new ObjectFactoryImplementation(targetClass.getClassLoader(), BOOTSTRAP);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    private record InjectedMethod(String name, Class<?> generic, Type specific, boolean isPublic) {}

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
                var specific = method.getGenericReturnType();
                methods.add(new InjectedMethod(name, generic, specific, (modifier & Modifier.PUBLIC) != 0));
            }
        }
    }

    static InjectedImplementation implement(Class<?> clazz) {
        if ((clazz.getModifiers() & Modifier.PUBLIC) == 0) {
            throw new RuntimeException("Class to instantiate must be public");
        }
        if ((clazz.getModifiers() & Modifier.FINAL) != 0) {
            throw new RuntimeException("Class to instantiate may not be final");
        }

        List<Type> injectedTypes = new ArrayList<>();
        List<Class<?>> injectedClassTypes = new ArrayList<>();

        var writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        var name = (ATTACHMENT_TARGET_NAME+"$"+clazz.getSimpleName()).replace('.', '/');
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC, name, null, org.objectweb.asm.Type.getInternalName(clazz), null);

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
            injectedTypes.add(type);
            injectedClassTypes.add(erased);
            targetCtorArgs.add(erased);
        }

        var methods = collectInjectedMethods(clazz);
        for (var method : methods) {
            var fieldName = "$"+method.name+"$syringe_injected_field";
            var field = writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, fieldName, method.generic().descriptorString(), null, null);
            field.visitEnd();
            injectedTypes.add(method.specific());
            injectedClassTypes.add(method.generic());
            var methodImpl = writer.visitMethod(
                    (method.isPublic()? Opcodes.ACC_PUBLIC : Opcodes.ACC_PROTECTED) | Opcodes.ACC_FINAL,
                    method.name,
                    MethodType.methodType(method.generic()).descriptorString(),
                    null,
                    null
            );
            methodImpl.visitCode();
            methodImpl.visitVarInsn(Opcodes.ALOAD, 0);
            methodImpl.visitFieldInsn(Opcodes.GETFIELD, name, fieldName, method.generic().descriptorString());
            methodImpl.visitInsn(Opcodes.ARETURN);
            methodImpl.visitMaxs(0, 0);
            methodImpl.visitEnd();
        }

        var ctorType = MethodType.methodType(void.class, injectedClassTypes);

        var ctor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", ctorType.descriptorString(), null, null);
        ctor.visitCode();
        int index = 1;
        for (var argType : targetCtorArgs) {
            var type = org.objectweb.asm.Type.getType(argType);
            index += type.getSize();
        }
        for (var method : methods) {
            ctor.visitVarInsn(Opcodes.ALOAD, 0);
            ctor.visitVarInsn(Opcodes.ALOAD, index);
            ctor.visitFieldInsn(Opcodes.PUTFIELD, name, "$"+method.name+"$syringe_injected_field", method.generic().descriptorString());
            index++;
        }
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        index = 1;
        for (var argType : targetCtorArgs) {
            var type = org.objectweb.asm.Type.getType(argType);
            var opcode = type.getOpcode(Opcodes.ILOAD);
            ctor.visitVarInsn(opcode, index);
            index += type.getSize();
        }
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, org.objectweb.asm.Type.getInternalName(clazz), "<init>", MethodType.methodType(void.class, targetCtorArgs).descriptorString(), false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();

        writer.visitEnd();

        var bytes = writer.toByteArray();
        try {
            var implementationLookup = ATTACHMENT_TARGET.defineHiddenClass(bytes, false);
            var ctorHandle = implementationLookup.findConstructor(implementationLookup.lookupClass(), ctorType);
            return new InjectedImplementation(ctorHandle, injectedTypes);
        } catch (IllegalAccessException | NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }
}
