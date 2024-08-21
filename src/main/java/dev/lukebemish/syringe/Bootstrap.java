package dev.lukebemish.syringe;

import dev.lukebemish.syringe.annotations.ModScope;
import net.neoforged.fml.loading.FMLLoader;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

final class Bootstrap {
    static final String ATTACHMENT_MODULE = "dev.lukebemish.syringe.attachment";
    static final String ATTACHMENT_TARGET_NAME = "dev.lukebemish.syringe.attachment.AttachmentTarget";
    static final MethodHandles.Lookup ATTACHMENT_TARGET;
    static final ObjectFactoryImplementation BOOTSTRAP;
    static final ObjectFactoryImplementation ROOT;

    static final List<InstantiatorDiscoverer> PER_MOD;

    private Bootstrap() {}

    static {
        var layer = FMLLoader.getGameLayer();
        try {
            var targetClass = layer.findLoader(Bootstrap.ATTACHMENT_MODULE).loadClass(Bootstrap.ATTACHMENT_TARGET_NAME);
            var lookupMethod = MethodHandles.publicLookup().findStatic(targetClass, "lookup", MethodType.methodType(MethodHandles.Lookup.class));
            ATTACHMENT_TARGET = (MethodHandles.Lookup) lookupMethod.invoke();

            var perMod = new ArrayList<InstantiatorDiscoverer>();
            var singleton= new ArrayList<InstantiatorDiscoverer>();

            ServiceLoader.load(layer, InstantiatorDiscoverer.class).stream().forEach(p -> {
                var discoverer = p.get();
                if (discoverer.getClass().isAnnotationPresent(ModScope.class)) {
                    perMod.add(discoverer);
                } else {
                    singleton.add(discoverer);
                }
            });

            PER_MOD = List.copyOf(perMod);

            BOOTSTRAP = new ObjectFactoryImplementation(targetClass.getClassLoader(), null);
            var configuration = ObjectFactory.Configuration.create();
            for (var discoverer : singleton) {
                discoverer.configure(configuration);
            }
            ROOT = BOOTSTRAP.newObjectFactory(configuration);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }
}
