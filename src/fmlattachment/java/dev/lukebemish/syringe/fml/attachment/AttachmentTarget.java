package dev.lukebemish.syringe.fml.attachment;

import net.neoforged.bus.api.IEventBus;

import java.lang.invoke.MethodHandles;

public final class AttachmentTarget {
    private AttachmentTarget() {}

    public static MethodHandles.Lookup lookup() {
        return MethodHandles.lookup();
    }

    public static IEventBus gameBus() {
        try {
            var neoForgeClass = Class.forName("net.neoforged.neoforge.common.NeoForge");
            var bus = MethodHandles.lookup().findStaticGetter(neoForgeClass, "EVENT_BUS", IEventBus.class);
            return (IEventBus) bus.invokeExact();
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }
}
