package dev.lukebemish.syringe.fml;

import dev.lukebemish.syringe.Assisted;
import dev.lukebemish.syringe.Component;
import dev.lukebemish.syringe.Instantiator;
import dev.lukebemish.syringe.Provides;
import jakarta.inject.Inject;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.neoforgespi.language.IModInfo;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Objects;

@Component(scopes = {ModScope.class, SyringeMod.class})
final class ModComponent {
    private static final String ATTACHMENT_MODULE = "dev.lukebemish.syringe.fml.attachment";
    private static final String ATTACHMENT_TARGET_NAME = "dev.lukebemish.syringe.fml.attachment.AttachmentTarget";

    private final Instantiator instantiator;
    private final ModContainer modContainer;
    private final IEventBus gameBus;

    {
        var layer = FMLLoader.getGameLayer();
        try {
            var targetClass = layer.findLoader(ATTACHMENT_MODULE).loadClass(ATTACHMENT_TARGET_NAME);
            var lookupMethod = MethodHandles.publicLookup().findStatic(targetClass, "lookup", MethodType.methodType(MethodHandles.Lookup.class));
            this.instantiator = Instantiator.builder().lookup((MethodHandles.Lookup) lookupMethod.invoke()).build();

            var gameBusMethod = MethodHandles.publicLookup().findStatic(targetClass, "gameBus", MethodType.methodType(IEventBus.class));
            this.gameBus = new SuperclassAllowingEventBus((IEventBus) gameBusMethod.invoke());
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    @Inject
    public ModComponent(@Assisted ModContainer modContainer) {
        this.modContainer = modContainer;
    }

    @Provides
    public ModContainer modContainer() {
        return modContainer;
    }

    @Provides
    public Instantiator instantiator() {
        return instantiator;
    }

    @Provides
    public IEventBus modBus() {
        return Objects.requireNonNull(modContainer.getEventBus());
    }

    @Provides @Game
    public IEventBus gameBus() {
        return Objects.requireNonNull(gameBus);
    }

    @Provides
    public IModInfo modInfo() {
        return modContainer.getModInfo();
    }

    @Provides
    public Dist dist() {
        return FMLLoader.getDist();
    }
}
