package poc.uiagent;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;

/** Reflection helpers so the agent compiles against Java 6 and still talks to JavaFX or log4j. */
public final class Reflect {
    private Reflect() { }

    public static Class<?> cls(String name) {
        try {
            return Class.forName(name, false, ClassLoader.getSystemClassLoader());
        } catch (Throwable e) {
            return null;
        }
    }

    public static boolean is(Object o, String className) {
        Class<?> c = cls(className);
        return c != null && o != null && c.isInstance(o);
    }

    public static Object staticField(String className, String field) {
        try {
            return cls(className).getField(field).get(null);
        } catch (Throwable e) {
            return null;
        }
    }

    /** Calls a public no-arg method, or returns null if anything is missing or fails. */
    public static Object call(Object target, String method) {
        return call(target, method, new Class<?>[0]);
    }

    public static Object call(Object target, String method, Class<?>[] types, Object... args) {
        if (target == null) return null;
        try {
            return find(target.getClass(), method, types).invoke(target, args);
        } catch (Throwable e) {
            return null;
        }
    }

    public static Object callStatic(String className, String method, Class<?>[] types, Object... args) {
        try {
            Class<?> c = cls(className);
            return c == null ? null : c.getMethod(method, types).invoke(null, args);
        } catch (Throwable e) {
            return null;
        }
    }

    /** A public method reachable through a public class or interface, so invoke works across modules. */
    static Method find(Class<?> c, String name, Class<?>[] types) throws NoSuchMethodException {
        Method m = c.getMethod(name, types);
        if (Modifier.isPublic(m.getDeclaringClass().getModifiers())) return m;
        for (Class<?> s = c; s != null; s = s.getSuperclass()) {
            if (Modifier.isPublic(s.getModifiers())) {
                try { return s.getMethod(name, types); } catch (NoSuchMethodException ignored) { }
            }
            for (Class<?> i : s.getInterfaces()) {
                try { if (Modifier.isPublic(i.getModifiers())) return i.getMethod(name, types); } catch (NoSuchMethodException ignored) { }
            }
        }
        return m;
    }

    public static Object proxy(String interfaceName, InvocationHandler h) {
        Class<?> i = cls(interfaceName);
        if (i == null) return null;
        return Proxy.newProxyInstance(i.getClassLoader() == null ? ClassLoader.getSystemClassLoader() : i.getClassLoader(), new Class<?>[] { i }, h);
    }

    /** Base handler that answers Object's methods itself so proxies can sit in collections. */
    public abstract static class Handler implements InvocationHandler {
        public final Object invoke(Object proxy, Method m, Object[] args) throws Throwable {
            String n = m.getName();
            if (m.getDeclaringClass() == Object.class) {
                if (n.equals("equals")) return Boolean.valueOf(proxy == args[0]);
                if (n.equals("hashCode")) return Integer.valueOf(System.identityHashCode(proxy));
                if (n.equals("toString")) return "ui-agent proxy";
            }
            try {
                return handle(n, args, m);
            } catch (Throwable e) {
                Agent.debug("proxy " + n + ": " + e);
                return dflt(m.getReturnType());
            }
        }

        protected abstract Object handle(String method, Object[] args, Method m) throws Throwable;

        protected static Object dflt(Class<?> rt) {
            if (rt == boolean.class) return Boolean.FALSE;
            if (rt == int.class) return Integer.valueOf(0);
            if (rt == long.class) return Long.valueOf(0);
            if (rt == void.class || !rt.isPrimitive()) return null;
            return Integer.valueOf(0);
        }
    }
}
