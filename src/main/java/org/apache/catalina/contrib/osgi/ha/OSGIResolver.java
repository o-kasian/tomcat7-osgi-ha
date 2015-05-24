package org.apache.catalina.contrib.osgi.ha;

import java.lang.ref.Reference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.servlet.ServletContext;
import org.apache.catalina.Container;
import org.apache.catalina.Context;
import org.apache.catalina.ha.session.ClusterManagerBase;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;

/**
 *
 * @author Oleg Kasian
 */
public class OSGIResolver {

    private static final Log LOG = LogFactory.getLog(OSGIResolver.class);
    public static final String BUNDLE_CONTEXT_CLASSNAME = "org.osgi.framework.BundleContext";

    private final Object cachingMonitor = new Object();
    private ClassLoader[] cache;
    private final ClusterManagerBase wrappedManager;
    
    private final String attributeName;

    public OSGIResolver(final ClusterManagerBase wrappedManager, final String attributeName) {
        this.wrappedManager = wrappedManager;
        this.attributeName = attributeName;
    }

    public ClassLoader[] getClassLoaders() {
        final Container container = wrappedManager.getContainer();
        final ClassLoader[] classLoaders = ClusterManagerBase.getClassLoaders(container);
        if (container == null || !(container instanceof Context)) {
            LOG.warn("DeltaManager does not have a container");
            return classLoaders;
        }
        final Context context = (Context) container;
        final ServletContext servletContext = context.getServletContext();
        if (servletContext == null) {
            LOG.warn("ServletContext is not set");
            return classLoaders;
        }
        ClassLoader[] cached = getCachedClassLoaders();
        if (cached != null) {
            return cached;
        }
        synchronized (cachingMonitor) {
            cached = getCachedClassLoaders();
            if (cached != null) {
                return cached;
            }
            final List<ClassLoader> cl = new ArrayList<ClassLoader>();
            cl.add(servletContext.getClassLoader());
            final ClassLoader[] result = merge(classLoaders, 
                    getAttributeClassLoader(servletContext, attributeName),
                    servletContext.getClassLoader()
            );
            putToCache(result);
            return result;
        }
    }

    ;

    protected void putToCache(final ClassLoader[] classLoaders) {
        cache = classLoaders;
    }

    protected ClassLoader[] getCachedClassLoaders() {
        return cache;
    }

    protected ClassLoader getAttributeClassLoader(final ServletContext ctx, final String attr) {
        final Object cl = ctx.getAttribute(attr);
        if (cl == null) {
            return null;
        }
        return adapt(cl);
    }

    public void wipe() {
        synchronized (cachingMonitor) {
            cache = null;
        }
    }

    private ClassLoader[] merge(ClassLoader[] src, ClassLoader... cls) {
        final Set<ClassLoader> s = new LinkedHashSet<ClassLoader>();
        for (final ClassLoader cl : src) {
            if (cl == null) {
                continue;
            }
            s.add(cl);
        }
        for (final ClassLoader cl : cls) {
            if (cl == null) {
                continue;
            }
            s.add(cl);
        }
        return s.toArray(new ClassLoader[]{});
    }

    private ClassLoader adapt(final Object cl) {
        ClassLoader classLoader = null;
        if (cl instanceof ClassLoader) {
            classLoader = (ClassLoader) cl;
        } else if (cl instanceof Reference) {
            final Object ref = ((Reference) cl).get();
            if (ref instanceof ClassLoader) {
                classLoader = (ClassLoader) ref;
            }
        } else if (isBundleContext(cl.getClass())) {
            try {
                final Method getBundle = cl.getClass().getDeclaredMethod("getBundle");
                getBundle.setAccessible(true);
                final Object bundle = getBundle.invoke(cl);
                classLoader = new BundleClassLoader(bundle);
            } catch (ReflectiveOperationException ex) {
                Logger.getLogger(OSGIResolver.class.getName()).log(Level.SEVERE, null, ex);
            }
        }
        if (classLoader == null) {
            LOG.warn("Expected attribute to be either "
                    + "BundleContext, Reference to ClassLoader or ClassLoader instance, but was "
                    + cl.getClass());
        }
        return classLoader;
    }

    private boolean isBundleContext(final Class cls) {
        if (cls.getInterfaces() == null) {
            return false;
        }
        for (final Class iface : getInterfaces(cls)) {
            if (iface.getName().equals(BUNDLE_CONTEXT_CLASSNAME)) {
                return true;
            }
        }
        return false;
    }
    
    private Class[] getInterfaces(final Class ... cls) {
        final List allInterfaces = new ArrayList();
        for (final Class cl : cls) {
            allInterfaces.add(cl);
            allInterfaces.addAll(Arrays.asList(getInterfaces(cl.getInterfaces())));
        }
        return (Class[]) allInterfaces.toArray(new Class[allInterfaces.size()]);
    }

    private class BundleClassLoader extends ClassLoader {

        private final Method loadClass;
        private final Object bundle;

        public BundleClassLoader(final Object bundle) throws ReflectiveOperationException {
            this.bundle = bundle;
            loadClass = bundle.getClass().getDeclaredMethod("loadClass", String.class);
            loadClass.setAccessible(true);
        }

        @Override
        public Class<?> loadClass(final String name) throws ClassNotFoundException {
            try {
                return (Class<?>) loadClass.invoke(bundle, name);
            } catch (ReflectiveOperationException ex) {
                throw new ClassNotFoundException(ex.getMessage(), ex);
            }
        }

    }
}
