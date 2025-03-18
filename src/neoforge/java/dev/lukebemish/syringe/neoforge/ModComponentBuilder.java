package dev.lukebemish.syringe.neoforge;

import dev.lukebemish.syringe.ObjectFactory;
import jakarta.inject.Named;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.loading.FMLLoader;

import java.lang.annotation.Annotation;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;
import java.util.function.UnaryOperator;

final class ModComponentBuilder {
    private static final String ATTACHMENT_MODULE = "dev.lukebemish.syringe.neoforge.attachment";
    private static final String ATTACHMENT_GAME_COMPONENT = "dev.lukebemish.syringe.neoforge.attachment.GameComponent";
    private static final String ATTACHMENT_MOD_COMPONENT = "dev.lukebemish.syringe.neoforge.attachment.ModComponent";

    private static final String SUPERCLASS_ALLOWING_EVENT_BUS = "dev.lukebemish.syringe.neoforge.attachment.SuperclassAllowingEventBus";

    private static final Class<?> GAME_COMPONENT;
    private static final MethodHandle GAME_COMPONENT_OBJECT_FACTORY;
    private static final Class<?> MOD_COMPONENT;
    private static final MethodHandle MOD_COMPONENT_OBJECT_FACTORY;

    private static final MethodHandle BUS_CTOR;

    static {
        var layer = FMLLoader.getGameLayer();
        try {
            GAME_COMPONENT = layer.findLoader(ATTACHMENT_MODULE).loadClass(ATTACHMENT_GAME_COMPONENT);
            GAME_COMPONENT_OBJECT_FACTORY = MethodHandles.publicLookup().findVirtual(GAME_COMPONENT, "scopedObjectFactory", MethodType.methodType(ObjectFactory.class));

            MOD_COMPONENT = layer.findLoader(ATTACHMENT_MODULE).loadClass(ATTACHMENT_MOD_COMPONENT);
            MOD_COMPONENT_OBJECT_FACTORY = MethodHandles.publicLookup().findVirtual(MOD_COMPONENT, "scopedObjectFactory", MethodType.methodType(ObjectFactory.class));

            var targetClass = layer.findLoader(ATTACHMENT_MODULE).loadClass(SUPERCLASS_ALLOWING_EVENT_BUS);
            BUS_CTOR = MethodHandles.publicLookup().findConstructor(targetClass, MethodType.methodType(void.class, IEventBus.class)).asType(MethodType.methodType(IEventBus.class, IEventBus.class));
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    static final UnaryOperator<IEventBus> BUS_OPERATOR = (IEventBus bus) -> {
        try {
            return (IEventBus) BUS_CTOR.invokeExact(bus);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    };

    static ObjectFactory handleFor(ModContainer container, ObjectFactory root) {
        try {
            var modId = container.getModId();
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
