package dev.lukebemish.syringe.fabric.attachment;

import dev.lukebemish.syringe.Component;
import dev.lukebemish.syringe.Instantiator;
import dev.lukebemish.syringe.ObjectFactory;
import dev.lukebemish.syringe.Provides;
import dev.lukebemish.syringe.common.GameScope;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import net.fabricmc.loader.api.FabricLoader;

import java.lang.invoke.MethodHandles;

@Singleton
@Component(scopes = GameScope.class)
final class GameComponent {
    private final ObjectFactory factory;

    @Inject
    public GameComponent(ObjectFactory rootFactory) {
        this.factory = rootFactory.within(this);
    }

    @Provides
    public FabricLoader fabricLoader() {
        return FabricLoader.getInstance();
    }

    private static final Instantiator instantiator = Instantiator.builder().lookup(MethodHandles.lookup()).build();

    @Provides
    public Instantiator instantiator() {
        return instantiator;
    }

    public ObjectFactory scopedObjectFactory() {
        return factory;
    }
}
