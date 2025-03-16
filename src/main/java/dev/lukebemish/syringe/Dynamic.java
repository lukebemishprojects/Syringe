package dev.lukebemish.syringe;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.RetentionPolicy.RUNTIME;

@Documented
@Retention(RUNTIME)
@Target(ElementType.PARAMETER)
public @interface Dynamic {
    // TODO: implement. This is used in providers and allows them to accept a qualifier annotation.
}
