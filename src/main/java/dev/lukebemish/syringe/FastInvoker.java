package dev.lukebemish.syringe;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

interface FastInvoker {
    Object invoke(Object[] args);
    MethodHandle handle();

    static FastInvoker create(MethodHandle handle) {
        handle = handle.asFixedArity();
        var targetType = handle.type().changeReturnType(Object.class);
        for (int i = 0; i < handle.type().parameterCount(); i++) {
            targetType = targetType.changeParameterType(i, Object.class);
        }

        var name = FastInvoker.class.getName().replace('.', '/')+"$Invoker";
        var writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(
            Opcodes.V21,
            Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
            name,
            null,
            Type.getInternalName(Object.class),
            new String[] {Type.getInternalName(FastInvoker.class)}
        );

        var ctor = writer.visitMethod(
            0,
            "<init>",
            "()V",
            null,
            null
        );
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, Type.getInternalName(Object.class), "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();

        var implementation = writer.visitMethod(
            Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
            "invoke",
            Type.getMethodDescriptor(Type.getType(Object.class), Type.getType(Object[].class)),
            null,
            null
        );

        implementation.visitCode();
        for (int i = 0; i < targetType.parameterCount(); i++) {
            implementation.visitVarInsn(Opcodes.ALOAD, 1);
            implementation.visitLdcInsn(i);
            implementation.visitInsn(Opcodes.AALOAD);
        }
        implementation.visitInvokeDynamicInsn(
            "apply",
            targetType.descriptorString(),
            new Handle(
                Opcodes.H_INVOKESTATIC,
                Type.getInternalName(FastInvoker.class),
                "invokeHandle",
                MethodType.methodType(CallSite.class, MethodHandles.Lookup.class, String.class, MethodType.class).descriptorString(),
                true
            )
        );
        implementation.visitInsn(Opcodes.ARETURN);
        implementation.visitMaxs(0, 0);
        implementation.visitEnd();

        var handleImplementation = writer.visitMethod(
            Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
            "handle",
            Type.getMethodDescriptor(Type.getType(MethodHandle.class)),
            null,
            null
        );
        handleImplementation.visitCode();
        handleImplementation.visitLdcInsn(new ConstantDynamic(
            "_",
            MethodHandle.class.descriptorString(),
            new Handle(
                Opcodes.H_INVOKESTATIC,
                Type.getInternalName(MethodHandles.class),
                "classData",
                MethodType.methodType(Object.class, MethodHandles.Lookup.class, String.class, Class.class).descriptorString(),
                false
            )
        ));
        handleImplementation.visitInsn(Opcodes.ARETURN);
        handleImplementation.visitMaxs(0, 0);
        handleImplementation.visitEnd();

        var bytes = writer.toByteArray();
        try {
            var lookup = MethodHandles.lookup().defineHiddenClassWithClassData(bytes, handle, true);
            var ctorHandle = lookup.findConstructor(lookup.lookupClass(), MethodType.methodType(void.class));
            return (FastInvoker) ctorHandle.invoke();
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unused")
    static CallSite invokeHandle(MethodHandles.Lookup caller, String name, MethodType factoryType) {
        try {
            var classData = MethodHandles.classData(caller, "_", MethodHandle.class);
            var handle = classData.asType(factoryType);
            return new ConstantCallSite(handle);

        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }
}
