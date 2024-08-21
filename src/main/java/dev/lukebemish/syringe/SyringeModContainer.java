package dev.lukebemish.syringe;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.EventBusErrorMessage;
import net.neoforged.bus.api.BusBuilder;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.fml.ModLoadingException;
import net.neoforged.fml.ModLoadingIssue;
import net.neoforged.fml.event.IModBusEvent;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.neoforgespi.language.IModInfo;
import net.neoforged.neoforgespi.language.ModFileScanData;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

class SyringeModContainer extends ModContainer {
    private static final Logger LOGGER = LogManager.getLogger();

    private final ModFileScanData scanResults;
    private final IEventBus eventBus;
    private final Module module;
    private final ObjectFactory.Configuration objectFactoryConfiguration;
    private @Nullable ObjectFactoryImplementation objectFactory;
    private final List<Class<?>> modClasses;

    public SyringeModContainer(IModInfo info, List<String> entrypoints, ModFileScanData scanResults, ModuleLayer gameLayer) {
        super(info);
        this.scanResults = scanResults;
        this.eventBus = new SuperclassAllowingEventBus(BusBuilder.builder()
                .setExceptionHandler((bus, event, listeners, index, throwable) ->
                    LOGGER.error(new EventBusErrorMessage(event, index, listeners, throwable))
                )
                .markerType(IModBusEvent.class)
                .allowPerPhasePost()
                .build()
        );
        this.module = gameLayer.findModule(info.getOwningFile().moduleName()).orElseThrow();
        this.objectFactoryConfiguration = ObjectFactory.Configuration.create();
        this.objectFactoryConfiguration.bindService(IModInfo.class, info);
        this.objectFactoryConfiguration.bindService(IEventBus.class, eventBus);
        this.objectFactoryConfiguration.bindService(ModContainer.class, this);
        this.objectFactoryConfiguration.bindService(Dist.class, FMLLoader.getDist());

        // Load classes
        var context = ModLoadingContext.get();
        try {
            context.setActiveContainer(this);

            for (var discoverer : Bootstrap.PER_MOD) {
                discoverer.configure(this.objectFactoryConfiguration);
            }

            modClasses = new ArrayList<>();

            for (var entrypoint : entrypoints) {
                try {
                    var cls = Class.forName(module, entrypoint);
                    modClasses.add(cls);
                } catch (Throwable e) {
                    LOGGER.error("Failed to load class {}", entrypoint, e);
                    throw new ModLoadingException(ModLoadingIssue.error("fml.modloadingissue.failedtoloadmodclass").withCause(e).withAffectedMod(info));
                }
            }
        } finally {
            context.setActiveContainer(null);
        }
    }

    private final Object constructionLock = new Object();

    @Override
    protected void constructMod() {
        synchronized (this.constructionLock) {
            if (this.objectFactory != null) {
                LOGGER.error("Mod was already constructed. ModID: {}", getModId());
                throw new IllegalStateException("Mod already constructed");
            }
            var serviceFactory = Bootstrap.ROOT.newObjectFactory(this.objectFactoryConfiguration);
            var modConfiguration = ObjectFactory.Configuration.create();
            for (var modClass : modClasses) {
                modConfiguration.bindService(modClass);
            }
            this.objectFactory = serviceFactory.newObjectFactory(modConfiguration);
        }
        for (var modClass : modClasses) {
            var ignored = this.objectFactory.findService(modClass);
        }

        // TODO: EBS
    }

    @Override
    public IEventBus getEventBus() {
        return eventBus;
    }
}
