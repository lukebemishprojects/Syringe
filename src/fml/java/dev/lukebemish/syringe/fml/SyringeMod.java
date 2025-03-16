package dev.lukebemish.syringe.fml;

import jakarta.inject.Scope;
import net.neoforged.api.distmarker.Dist;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Scope
@Documented
public @interface SyringeMod {
    String value();

    Dist[] dist() default {Dist.CLIENT, Dist.DEDICATED_SERVER};
}
