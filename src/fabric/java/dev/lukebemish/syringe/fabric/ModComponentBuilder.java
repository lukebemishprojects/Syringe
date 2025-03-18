package dev.lukebemish.syringe.fabric;

import dev.lukebemish.syringe.ObjectFactory;
import jakarta.inject.Named;

import java.lang.annotation.Annotation;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;

class ModComponentBuilder {
    private static final String ATTACHMENT_GAME_COMPONENT = "dev.lukebemish.syringe.fabric.attachment.GameComponent";
    private static final String ATTACHMENT_MOD_COMPONENT = "dev.lukebemish.syringe.fabric.attachment.ModComponent";

    private static final Class<?> GAME_COMPONENT;
    private static final MethodHandle GAME_COMPONENT_OBJECT_FACTORY;
    private static final Class<?> MOD_COMPONENT;
    private static final MethodHandle MOD_COMPONENT_OBJECT_FACTORY;

    static {
        try {
            var lookup = MethodHandles.lookup();

            GAME_COMPONENT = Class.forName(ATTACHMENT_GAME_COMPONENT);
            GAME_COMPONENT_OBJECT_FACTORY = MethodHandles.privateLookupIn(GAME_COMPONENT, lookup).findVirtual(GAME_COMPONENT, "scopedObjectFactory", MethodType.methodType(ObjectFactory.class));

            MOD_COMPONENT = Class.forName(ATTACHMENT_MOD_COMPONENT);
            MOD_COMPONENT_OBJECT_FACTORY = MethodHandles.privateLookupIn(MOD_COMPONENT, lookup).findVirtual(MOD_COMPONENT, "scopedObjectFactory", MethodType.methodType(ObjectFactory.class));
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    static ObjectFactory handleFor(String modId, ObjectFactory root) {
        try {
            var gameFactory = (ObjectFactory) GAME_COMPONENT_OBJECT_FACTORY.invoke(root.instance(GAME_COMPONENT));
            return (ObjectFactory) MOD_COMPONENT_OBJECT_FACTORY.invoke(gameFactory.instance(List.of(new Named() {
                @Override
                public Class<? extends Annotation> annotationType() {
                    return Named.class;
                }

                @Override
                public String value() {
                    return modId;
                }

                @Override
                public boolean equals(Object obj) {
                    return obj instanceof Named named && named.value().equals(modId);
                }

                @Override
                public int hashCode() {
                    return (127 * "value".hashCode()) ^ modId.hashCode();
                }

                @Override
                public String toString() {
                    return "@" + Named.class.getName() + "(value=" + modId + ")";
                }
            }), MOD_COMPONENT));
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }
}
