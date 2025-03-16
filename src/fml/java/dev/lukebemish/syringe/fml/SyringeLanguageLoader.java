package dev.lukebemish.syringe.fml;

import com.google.auto.service.AutoService;
import dev.lukebemish.syringe.ObjectFactory;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModLoadingException;
import net.neoforged.fml.javafmlmod.AutomaticEventSubscriber;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.neoforgespi.language.IModInfo;
import net.neoforged.neoforgespi.language.IModLanguageLoader;
import net.neoforged.neoforgespi.language.ModFileScanData;

import java.lang.annotation.ElementType;
import java.util.Comparator;

@AutoService(IModLanguageLoader.class)
public class SyringeLanguageLoader implements IModLanguageLoader {
    private final ObjectFactory syringeObjectFactory = ObjectFactory.create();

    @Override
    public String name() {
        return "syringe";
    }

    @Override
    public String version() {
        return SyringeLanguageLoader.class.getPackage().getImplementationVersion();
    }

    @Override
    public ModContainer loadMod(IModInfo info, ModFileScanData modFileScanResults, ModuleLayer layer) throws ModLoadingException {
        var modClasses = modFileScanResults.getAnnotatedBy(SyringeMod.class, ElementType.TYPE)
            .filter(data -> data.annotationData().get("value").equals(info.getModId()))
            .filter(ad -> AutomaticEventSubscriber.getSides(ad.annotationData().get("dist")).contains(FMLLoader.getDist()))
            .sorted(Comparator.comparingInt(ad -> -AutomaticEventSubscriber.getSides(ad.annotationData().get("dist")).size()))
            .map(ad -> ad.clazz().getClassName())
            .toList();
        return new SyringeModContainer(info, modClasses, modFileScanResults, layer, syringeObjectFactory);
    }
}
