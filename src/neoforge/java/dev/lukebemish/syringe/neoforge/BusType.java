package dev.lukebemish.syringe.neoforge;

import jakarta.inject.Qualifier;
import net.neoforged.fml.common.EventBusSubscriber;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@Qualifier
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface BusType {
    EventBusSubscriber.Bus value();
}
