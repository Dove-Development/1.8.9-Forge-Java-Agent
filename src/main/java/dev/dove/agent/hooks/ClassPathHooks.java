package dev.dove.agent.hooks;

import dev.dove.agent.DoveAgent;
import dev.dove.agent.util.AgentLog;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarFile;

/**
 * Stand-ins for the "system class loader is a URLClassLoader" assumption Java 8 code makes.
 *
 * <p>On Java 9+ the application class loader is not a {@link URLClassLoader}. LaunchWrapper casts
 * it to read the class path, and FML and many mods reflectively call {@code URLClassLoader#addURL}
 * on it to add jars (tweakers, Mixin, library loaders). These hooks keep both working.
 */
public final class ClassPathHooks {

    public static final String OWNER = "dev/dove/agent/hooks/ClassPathHooks";

    private static final Set<String> ADDED = ConcurrentHashMap.newKeySet();
    private static volatile SystemClassPathView view;

    private ClassPathHooks() {
    }

    /** Replaces {@code (URLClassLoader) value}. */
    public static URLClassLoader castToUrlClassLoader(Object value) {
        if (value == null || value instanceof URLClassLoader) {
            return (URLClassLoader) value;
        }
        if (value == ClassLoader.getSystemClassLoader()) {
            return systemView();
        }
        throw new ClassCastException("class " + value.getClass().getName() + " cannot be cast to class java.net.URLClassLoader");
    }

    /** Replaces {@code Method.invoke} in classes that reflect on {@code URLClassLoader#addURL}. */
    public static Object invoke(Method method, Object target, Object[] args)
            throws IllegalAccessException, InvocationTargetException {
        if (target != null && target == ClassLoader.getSystemClassLoader() && !(target instanceof URLClassLoader)
                && isAddUrl(method) && args != null && args.length == 1 && args[0] instanceof URL url) {
            addToSystemClassPath(url);
            return null;
        }
        return method.invoke(target, args);
    }

    /** Appends a jar to the application class loader, like {@code URLClassLoader#addURL} did on Java 8. */
    public static void addToSystemClassPath(URL url) {
        Path path;
        try {
            path = Path.of(url.toURI());
        } catch (URISyntaxException | IllegalArgumentException e) {
            AgentLog.warn("Cannot add " + url + " to the class path: not a local file");
            return;
        }
        String key = path.toAbsolutePath().normalize().toString();
        if (!ADDED.add(key)) {
            return;
        }
        File file = path.toFile();
        if (!file.isFile()) {
            // Instrumentation can only append jar files; directories only show up in dev setups.
            AgentLog.warn("Cannot add " + file + " to the class path: only jar files are supported");
            ADDED.remove(key);
            return;
        }
        try {
            DoveAgent.instrumentation().appendToSystemClassLoaderSearch(new JarFile(file));
        } catch (IOException e) {
            AgentLog.error("Cannot add " + file + " to the class path", e);
            ADDED.remove(key);
            return;
        }
        SystemClassPathView current = view;
        if (current != null) {
            current.track(url);
        }
        AgentLog.debug("Added " + file + " to the class path");
    }

    private static boolean isAddUrl(Method method) {
        return method != null && method.getName().equals("addURL") && method.getDeclaringClass() == URLClassLoader.class;
    }

    private static SystemClassPathView systemView() {
        SystemClassPathView current = view;
        if (current == null) {
            synchronized (ClassPathHooks.class) {
                current = view;
                if (current == null) {
                    current = new SystemClassPathView(classPathUrls(), ClassLoader.getSystemClassLoader());
                    view = current;
                }
            }
        }
        return current;
    }

    private static URL[] classPathUrls() {
        List<URL> urls = new ArrayList<>();
        for (String entry : System.getProperty("java.class.path", "").split(File.pathSeparator)) {
            if (entry.isEmpty()) {
                continue;
            }
            try {
                urls.add(Path.of(entry).toUri().toURL());
            } catch (MalformedURLException | RuntimeException e) {
                AgentLog.warn("Skipping class path entry " + entry + ": " + e);
            }
        }
        return urls.toArray(URL[]::new);
    }

    /**
     * What Java 8 code gets when it casts the system class loader to URLClassLoader: its URLs are
     * the class path, and adding a URL appends to the real system class loader. It never loads
     * classes itself, since everything it can see is also visible to its parent.
     */
    static final class SystemClassPathView extends URLClassLoader {

        static {
            ClassLoader.registerAsParallelCapable();
        }

        SystemClassPathView(URL[] urls, ClassLoader parent) {
            super("dove-system-class-path", urls, parent);
        }

        @Override
        protected void addURL(URL url) {
            addToSystemClassPath(url);
        }

        void track(URL url) {
            super.addURL(url);
        }
    }
}
