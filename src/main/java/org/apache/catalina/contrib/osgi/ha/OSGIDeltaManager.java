package org.apache.catalina.contrib.osgi.ha;

import java.lang.reflect.Field;
import javax.servlet.ServletContext;
import javax.servlet.ServletContextAttributeEvent;
import javax.servlet.ServletContextAttributeListener;
import org.apache.catalina.Context;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.ha.ClusterManager;
import org.apache.catalina.ha.ClusterMessage;
import org.apache.catalina.ha.session.DeltaManager;

/**
 *
 * @author Oleg Kasian
 */
public class OSGIDeltaManager extends DeltaManager {

    private static final String[] COPY_PROPERTIES = new String[]{
        "expireSessionsOnShutdown", "notifySessionListenersOnReplication",
        "notifyContainerListenersOnReplication", "stateTransferTimeout",
        "sendAllSessions", "sendAllSessionsSize", "sendAllSessionsWaitTime",
        "stateTimestampDrop"
    };

    private volatile boolean initialized = true;
    private String attributeName = "org.osgi.framework.BundleContext";

    private OSGIResolver resolver;

    @Override
    public void messageDataReceived(final ClusterMessage cmsg) {
        //Messages are not processed untill fully initialized
        if (!initialized) {
            return;
        }
        super.messageDataReceived(cmsg);
    }

    @Override
    public ClassLoader[] getClassLoaders() {
        return resolver.getClassLoaders();
    }

    @Override
    public ClusterManager cloneFromTemplate() {
        final OSGIDeltaManager result = new OSGIDeltaManager();
        clone(result);
        for (final String fName : COPY_PROPERTIES) {
            try {
                final Field f = DeltaManager.class.getDeclaredField(fName);
                f.setAccessible(true);
                f.set(result, f.get(this));
            } catch (Exception e) {
                log.error("Failed to clone DeltaManager", e);
            }
        }
        result.initialized = initialized;
        result.attributeName = attributeName;
        return result;
    }

    @Override
    protected synchronized void startInternal() throws LifecycleException {
        resolver = new OSGIResolver(this, attributeName);
        super.startInternal();
        final Context context = (Context) container;
        final ServletContext servletContext = context.getServletContext();
        servletContext.addListener(new EventListener());
        if (!initialized) {
            if (servletContext.getAttribute(attributeName) != null) {
                initialize();
            }
        }
    }

    @Override
    public synchronized void getAllClusterSessions() {
        if (!initialized) {
            return;
        }
        super.getAllClusterSessions();
    }

    public void setInitialized(final boolean initialized) {
        this.initialized = initialized;
    }

    public void setAttributeName(final String attributeName) {
        this.attributeName = attributeName;
    }

    private synchronized void initialize() {
        this.initialized = true;
        getAllClusterSessions();
    }

    private void onAttribute(final ServletContextAttributeEvent scae, final boolean init) {
        if (!scae.getName().equals(attributeName)) {
            return;
        }
        resolver.wipe();
        if (init && !initialized) {
            initialize();
        }
    }

    private class EventListener implements ServletContextAttributeListener {

        @Override
        public void attributeAdded(final ServletContextAttributeEvent scae) {
            onAttribute(scae, true);
        }

        @Override
        public void attributeRemoved(final ServletContextAttributeEvent scae) {
            onAttribute(scae, false);
        }

        @Override
        public void attributeReplaced(final ServletContextAttributeEvent scae) {
            onAttribute(scae, true);
        }

    }

}
