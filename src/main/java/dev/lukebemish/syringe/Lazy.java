package dev.lukebemish.syringe;

import java.util.function.Supplier;

public sealed interface Lazy<T> extends Supplier<T> permits Memoize {

}
