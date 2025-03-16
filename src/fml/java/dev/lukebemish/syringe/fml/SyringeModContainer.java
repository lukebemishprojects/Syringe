package dev.lukebemish.syringe.fml;

import dev.lukebemish.syringe.ObjectFactory;
import net.neoforged.bus.EventBusErrorMessage;
import net.neoforged.bus.api.BusBuilder;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.fml.ModLoadingException;
import net.neoforged.fml.ModLoadingIssue;
import net.neoforged.fml.event.IModBusEvent;
import net.neoforged.neoforgespi.language.IModInfo;
import net.neoforged.neoforgespi.language.ModFileScanData;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;

class SyringeModContainer extends ModContainer {
    private static final Logger LOGGER = LogManager.getLogger();

    private final ModFileScanData scanResults;
    private final IEventBus eventBus;
    private final ObjectFactory objectFactory;
    private final List<Class<?>> modClasses;

    public SyringeModContainer(IModInfo info, List<String> entrypoints, ModFileScanData scanResults, ModuleLayer gameLayer, ObjectFactory syringeObjectFactory) {
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
        Module module = gameLayer.findModule(info.getOwningFile().moduleName()).orElseThrow();
        this.objectFactory = syringeObjectFactory.within(syringeObjectFactory.instance(ModComponent.class, this));

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

    private final Object constructionLock = new Object();
    private boolean isConstructed = false;

    @Override
    protected void constructMod() {
        synchronized (this.constructionLock) {
            if (isConstructed) {
                LOGGER.error("Mod was already constructed. ModID: {}", getModId());
                throw new IllegalStateException("Mod already constructed");
            }
            isConstructed = true;
        }
        for (var modClass : modClasses) {
            this.objectFactory.instance(modClass);
        }

        // TODO: EBS
    }

    @Override
    public IEventBus getEventBus() {
        return eventBus;
    }
}
