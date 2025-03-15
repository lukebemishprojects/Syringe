package dev.lukebemish.syringe;

import java.lang.invoke.MethodType;

record PackageMethodRef(String name, MethodType type, String packageName) {}
