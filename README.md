# Enabling tomcat session replication for apps with OSGI classloader.

Problem is that tomcat-catalina-ha responsible for session replication knows nothing about OSGI BundleCLassloaders, and therefore can't deserialize messages from other nodes.

To overcome this, following steps have to be done:

### 1. Export BundleClassLoader as a servlet context attribute:

An example for felix ExtHttpService:
```java

    private void exportClassLoader() throws ServletException {
        registerFilter(new Filter() {

            @Override
            public void init(FilterConfig fc) throws ServletException {
                final ServletContext ctx = fc.getServletContext();
                if (ctx.getClass().getName().equals("org.apache.felix.http.base.internal.context.ServletContextImpl")) {
                    try {
                        final Field f = ctx.getClass().getDeclaredField("context");
                        f.setAccessible(true);
                        final ServletContext original = (ServletContext) f.get(ctx);
                        original.setAttribute("osgi.bundle.classloader", Activator.class.getClassLoader());
                    } catch (final Exception ex) {
                        LOGGER.warn("Failed to export `osgi.bundle.classloader` attribute,"
                                + " HttpSession replication will not work", ex);
                    }
                } else {
                    ctx.setAttribute("osgi.bundle.classloader", Activator.class.getClassLoader());
                }
            }

            @Override
            public void doFilter(ServletRequest sr, ServletResponse sr1, FilterChain fc)
                    throws IOException, ServletException {
                //stub
            }

            @Override
            public void destroy() {
                //stub
            }

        }, "");
    }

```

### 2. Add tomcat7-osgi-ha **jar** file to tomcat **lib/** directory
### 3. Configure ```<Cluster/>``` section in **server.xml** like in example

```xml
	<Cluster className="org.apache.catalina.ha.tcp.SimpleTcpCluster">
	    <Manager className="org.apache.catalina.contrib.osgi.ha.OSGIDeltaManager"
		     initialized="false"
		     attributeName="osgi.bundle.classloader" />
		     
		  ...
	</Cluster>
```

Here

**attributeName** - is responsible for holding ```ClassLoader, BundleContext or Reference<ClassLoader>```
**initialized** - when set to false, messaging does not start untill atrribute appears in context

**org.apache.catalina.contrib.osgi.ha.OSGIDeltaManager** - is an extended **org.apache.catalina.ha.session.DeltaManager** though it supports all it's configuration
