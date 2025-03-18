package dev.lukebemish.syringe.neoforge.attachment;

import com.google.common.base.Suppliers;
import dev.lukebemish.syringe.Assisted;
import dev.lukebemish.syringe.Binds;
import dev.lukebemish.syringe.Component;
import dev.lukebemish.syringe.ObjectFactory;
import dev.lukebemish.syringe.Provides;
import dev.lukebemish.syringe.neoforge.BusType;
import dev.lukebemish.syringe.neoforge.ModScope;
import jakarta.inject.Inject;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforgespi.language.IModInfo;

import java.util.Objects;
import java.util.function.Supplier;

//@GameScope
@Component(scopes = {ModScope.class, Mod.class})
public abstract class ModComponent {
    private final String modId;
    private final ObjectFactory factory;
    private final Supplier<ModContainer> modContainer = Suppliers.memoize(() -> {
        var list = ModList.get();
        if (list == null) {
            throw new IllegalStateException("ModList is null; may have attempted to query the mod container too early");
        }
        var self = this;
        return list.getModContainerById(self.modId).orElseThrow();
    });

    @Inject
    public ModComponent(@Assisted String modId, ObjectFactory parentFactory) {
        this.modId = modId;
        this.factory = parentFactory.within(this);;

    }

    @Provides @ModScope
    public ModContainer modContainer() {
        return modContainer.get();
    }

    @Provides @ModScope
    public IEventBus modBus() {
        return Objects.requireNonNull(modContainer().getEventBus());
    }

    @Binds @BusType(EventBusSubscriber.Bus.MOD) @ModScope
    public abstract IEventBus modBusScoped(IEventBus bus);

    @Provides @ModScope
    public IModInfo modInfo() {
        return modContainer().getModInfo();
    }

    public ObjectFactory scopedObjectFactory() {
        return factory;
    }
}
