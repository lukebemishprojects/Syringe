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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

class SyringeModContainer extends ModContainer {
    private static final Logger LOGGER = LogManager.getLogger();

    private final ModFileScanData scanResults;
    private final IEventBus eventBus;
    private final Module module;
    private final ObjectFactoryImplementation objectFactory;
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
        this.objectFactory = new ObjectFactoryImplementation(module.getClassLoader(), InjectedImplementation.ROOT);
        this.objectFactory.registerServiceInstance(IEventBus.class, this.eventBus);
        this.objectFactory.registerServiceInstance(ModContainer.class, this);
        this.objectFactory.registerServiceInstance(Dist.class, FMLLoader.getDist());

        // Load classes
        var context = ModLoadingContext.get();
        try {
            context.setActiveContainer(this);

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

    @Override
    protected void constructMod() {
        Map<Class<?>, Object> instances = new HashMap<>();
        Map<Class<?>, Consumer<Object>> providerConsumers = new HashMap<>();
        for (var modClass : modClasses) {
            providerConsumers.put(modClass, objectFactory.registerServiceProviderInstance(modClass));
        }
        for (var modClass : modClasses) {
            try {
                var instance = objectFactory.newInstance(modClass);
                instances.put(modClass, instance);
            } catch (Throwable e) {
                LOGGER.error("Failed to create mod instance. ModID: {}, class {}", getModId(), modClass.getName(), e);
                throw new ModLoadingException(ModLoadingIssue.error("fml.modloadingissue.failedtoloadmod").withCause(e).withAffectedMod(modInfo));
            }
        }
        for (var modClass : modClasses) {
            providerConsumers.get(modClass).accept(instances.get(modClass));
        }

        // TODO: EBS
    }

    @Override
    public IEventBus getEventBus() {
        return eventBus;
    }
}
