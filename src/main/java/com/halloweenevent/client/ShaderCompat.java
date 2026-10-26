package com.halloweenevent.client;

import java.lang.reflect.Method;

/**
 * Detects an active Oculus/Iris shader pack through {@code net.irisshaders.iris.api.v0.IrisApi}
 * (present in oculus-mc1.20.1-1.7.0). Reflection keeps Oculus an optional, compile-free dependency.
 */
public final class ShaderCompat {
    private static boolean resolved;
    private static Object api;
    private static Method inUse;

    private ShaderCompat() {}

    public static boolean shaderPackInUse() {
        if (!resolved) {
            resolved = true;
            try {
                Class<?> c = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                api = c.getMethod("getInstance").invoke(null);
                inUse = c.getMethod("isShaderPackInUse");
            } catch (ReflectiveOperationException | LinkageError e) {
                api = null;
            }
        }
        if (api == null) return false;
        try {
            return (boolean) inUse.invoke(api);
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }
}
