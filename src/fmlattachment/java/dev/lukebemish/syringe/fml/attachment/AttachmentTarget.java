package dev.lukebemish.syringe.fml.attachment;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;

import java.lang.invoke.MethodHandles;

public final class AttachmentTarget {
    private AttachmentTarget() {}

    public static MethodHandles.Lookup lookup() {
        return MethodHandles.lookup();
    }

    public static IEventBus gameBus() {
        return NeoForge.EVENT_BUS;
    }
}
