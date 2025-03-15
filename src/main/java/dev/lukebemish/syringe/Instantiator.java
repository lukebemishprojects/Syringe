package dev.lukebemish.syringe;

import jakarta.inject.Provider;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class Instantiator {
    private final MethodHandles.Lookup lookup;

    public Instantiator(MethodHandles.Lookup lookup) {
        this.lookup = lookup;
    }

    public Instantiator() {
        this(MethodHandles.lookup());
    }

    MethodHandles.Lookup implementAbstract(Class<?> type, List<ObjectProvider.GetterInjection<?>> getterInjections, List<ObjectProvider.Binding<?>> bindings, Constructor<?> superCtor) {
        var name = Type.getInternalName(lookup.lookupClass())+"$$Instantiator$$"+type.getSimpleName();
        var cv = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cv.visit(Opcodes.V21, Opcodes.ACC_FINAL, name, null, Type.getInternalName(type), new String[0]);
        var ctorParams = new ArrayList<>(Arrays.asList(superCtor.getParameterTypes()));
        for (int i = 0; i < getterInjections.size(); i++) {
            var getter = getterInjections.get(i);
            Class<?> getterType = getter.provider() ? Provider.class : getter.type().type();
            ctorParams.add(getterType);
            var fv = cv.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "$$syringe$"+i, Type.getDescriptor(getterType), null, null);
            fv.visitEnd();
        }
        var ctorType = MethodType.methodType(void.class, ctorParams.toArray(Class[]::new));
        var mv = cv.visitMethod(Opcodes.ACC_PUBLIC, "<init>", ctorType.descriptorString(), null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        int localOffset = 1;
        for (var param : superCtor.getParameterTypes()) {
            localOffset+=Type.getType(param).getSize();
        }
        for (int i = 0; i < getterInjections.size(); i++) {
            var getter = getterInjections.get(i);
            Class<?> getterType = getter.provider() ? Provider.class : getter.type().type();
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitVarInsn(Opcodes.ALOAD, localOffset);
            mv.visitFieldInsn(Opcodes.PUTFIELD, name, "$$syringe$"+i, getterType.descriptorString());
            localOffset+=Type.getType(getterType).getSize();
        }
        localOffset = 1;
        for (var param : superCtor.getParameterTypes()) {
            mv.visitVarInsn(Type.getType(param).getOpcode(Opcodes.ILOAD), localOffset);
            localOffset+=Type.getType(param).getSize();
        }
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, Type.getInternalName(type), "<init>", Type.getConstructorDescriptor(superCtor), false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        // Implement the getters
        for (int i = 0; i < getterInjections.size(); i++) {
            var getter = getterInjections.get(i);
            var getterType = getter.provider() ? Provider.class : getter.type().type();
            var mv2 = cv.visitMethod(Opcodes.ACC_PUBLIC, getter.method().getName(), Type.getMethodDescriptor(getter.method()), null, null);
            mv2.visitCode();
            mv2.visitVarInsn(Opcodes.ALOAD, 0);
            mv2.visitFieldInsn(Opcodes.GETFIELD, name, "$$syringe$"+i, Type.getDescriptor(getterType));
            mv2.visitInsn(Opcodes.ARETURN);
            mv2.visitMaxs(0, 0);
            mv2.visitEnd();
        }

        // Implement the bindings
        for (var binding : bindings) {
            var mv2 = cv.visitMethod(Opcodes.ACC_PUBLIC, binding.method().getName(), Type.getMethodDescriptor(binding.method()), null, null);
            mv2.visitCode();
            mv2.visitVarInsn(Opcodes.ALOAD, 1);
            mv2.visitInsn(Opcodes.ARETURN);
            mv2.visitMaxs(0, 0);
            mv2.visitEnd();
        }

        try {
            return this.lookup.defineHiddenClass(cv.toByteArray(), false);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }
}
