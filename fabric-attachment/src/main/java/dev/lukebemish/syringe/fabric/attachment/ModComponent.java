package dev.lukebemish.syringe.fabric.attachment;

import com.google.common.base.Suppliers;
import dev.lukebemish.syringe.Component;
import dev.lukebemish.syringe.Dynamic;
import dev.lukebemish.syringe.ObjectFactory;
import dev.lukebemish.syringe.Provides;
import dev.lukebemish.syringe.common.GameScope;
import dev.lukebemish.syringe.common.ModScope;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.fabricmc.loader.api.metadata.ModOrigin;

import java.util.function.Supplier;

@GameScope
@Component(scopes = ModScope.class)
final class ModComponent {
    private final String modId;
    private final ObjectFactory factory;
    private final Supplier<ModContainer> modContainer = Suppliers.memoize(() -> {
        var self = this;
        return FabricLoader.getInstance().getModContainer(self.modId).orElseThrow();
    });

    @Inject
    public ModComponent(@Dynamic Named modId, ObjectFactory parentFactory) {
        this.modId = modId.value();
        this.factory = parentFactory.within(this);
    }

    @Provides @ModScope
    public ModContainer modContainer() {
        return modContainer.get();
    }

    @Provides @ModScope
    public ModMetadata modMetadata() {
        return modContainer().getMetadata();
    }

    @Provides @ModScope
    public ModOrigin modOrigin() {
        return modContainer().getOrigin();
    }

    public ObjectFactory scopedObjectFactory() {
        return factory;
    }
}
