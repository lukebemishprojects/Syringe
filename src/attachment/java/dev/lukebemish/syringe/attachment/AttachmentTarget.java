package dev.lukebemish.syringe.attachment;

import java.lang.invoke.MethodHandles;

public final class AttachmentTarget {
    private AttachmentTarget() {}
    
    public static MethodHandles.Lookup lookup() {
        return MethodHandles.lookup();
    }
}
