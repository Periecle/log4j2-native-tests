package org.graalvm.log4j2;

import com.oracle.svm.core.annotate.Alias;
import com.oracle.svm.core.annotate.RecomputeFieldValue;
import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.status.StatusLogger;
import org.apache.logging.log4j.util.LoaderUtil;
import org.apache.logging.log4j.util.OsgiServiceLocator;

import java.lang.invoke.MethodHandles.Lookup;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.Spliterator;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

@TargetClass(className = "org.apache.logging.log4j.util.ServiceLoaderUtil")
public final class NativeServiceLoaderUtil {

    @Alias
    @RecomputeFieldValue(kind = RecomputeFieldValue.Kind.FromAlias)
    private static int MAX_BROKEN_SERVICES = 8;

    private NativeServiceLoaderUtil() {
    }

    /**
     * Retrieves the available services from the caller's classloader.
     *
     * Broken services will be ignored.
     *
     * @param <T>         The service type.
     * @param serviceType The class of the service.
     * @param lookup      The calling class data.
     * @return A stream of service instances.
     */
    @Substitute
    public static <T> Stream<T> loadServices(final Class<T> serviceType, Lookup lookup) {
        return loadServices(serviceType, lookup, false);
    }

    /**
     * Retrieves the available services from the caller's classloader and possibly
     * the thread context classloader.
     *
     * Broken services will be ignored.
     *
     * @param <T>         The service type.
     * @param serviceType The class of the service.
     * @param lookup      The calling class data.
     * @param useTccl     If true the thread context classloader will also be used.
     * @return A stream of service instances.
     */
    @Substitute
    public static <T> Stream<T> loadServices(final Class<T> serviceType, Lookup lookup, boolean useTccl) {
        return loadServices(serviceType, lookup, useTccl, true);
    }

    @Substitute
    static <T> Stream<T> loadServices(final Class<T> serviceType, final Lookup lookup, final boolean useTccl,
                                      final boolean verbose) {
        final ClassLoader classLoader = lookup.lookupClass().getClassLoader();
        Stream<T> services = loadClassloaderServices(serviceType, lookup, classLoader, verbose);
        if (useTccl) {
            final ClassLoader contextClassLoader = LoaderUtil.getThreadContextClassLoader();
            if (contextClassLoader != classLoader) {
                services = Stream.concat(services,
                        loadClassloaderServices(serviceType, lookup, contextClassLoader, verbose));
            }
        }
        if (OsgiServiceLocator.isAvailable()) {
            services = Stream.concat(services, OsgiServiceLocator.loadServices(serviceType, lookup, verbose));
        }
        final Set<Class<?>> classes = new HashSet<>();
        // only the first occurrence of a class
        return services.filter(new Predicate<T>() {
            @Override
            public boolean test(T service) {
                return classes.add(service.getClass());
            }
        });
    }

    @Substitute
    static <T> Stream<T> loadClassloaderServices(final Class<T> serviceType, final Lookup lookup,
                                                 final ClassLoader classLoader, final boolean verbose) {
        return StreamSupport.stream(new ServiceLoaderSpliterator<>(serviceType, lookup, classLoader, verbose), false);
    }

    @Substitute
    static <T> Iterable<T> callServiceLoader(Lookup lookup, Class<T> serviceType, ClassLoader classLoader,
                                             boolean verbose) {
        try {
            final ServiceLoader<T> serviceLoader;
            if (System.getSecurityManager() == null) {
                serviceLoader = ServiceLoader.load(serviceType, classLoader);
            } else {
                PrivilegedAction<ServiceLoader<T>> action = new PrivilegedAction<ServiceLoader<T>>() {
                    @Override
                    public ServiceLoader<T> run() {
                        return ServiceLoader.load(serviceType, classLoader);
                    }
                };
                serviceLoader = AccessController.doPrivileged(action);
            }
            return serviceLoader;
        } catch (Throwable e) {
            if (verbose) {
                StatusLogger.getLogger().error("Unable to load services for service {}", serviceType, e);
            }
        }
        return Collections.emptyList();
    }


    @Substitute
    private static class ServiceLoaderSpliterator<S> implements Spliterator<S> {

        private final Iterator<S> serviceIterator;
        private final Logger logger;
        private final String serviceName;

        @Substitute
        public ServiceLoaderSpliterator(final Class<S> serviceType, final Lookup lookup, final ClassLoader classLoader,
                                        final boolean verbose) {
            this.serviceIterator = callServiceLoader(lookup, serviceType, classLoader, verbose).iterator();
            this.logger = verbose ? StatusLogger.getLogger() : null;
            this.serviceName = serviceType.toString();
        }

        @Override
        @Substitute
        public boolean tryAdvance(Consumer<? super S> action) {
            int i = MAX_BROKEN_SERVICES;
            while (i-- > 0) {
                try {
                    if (serviceIterator.hasNext()) {
                        action.accept(serviceIterator.next());
                        return true;
                    }
                } catch (ServiceConfigurationError | LinkageError e) {
                    if (logger != null) {
                        logger.warn("Unable to load service class for service {}", serviceName, e);
                    }
                } catch (Throwable e) {
                    if (logger != null) {
                        logger.warn("Unable to load service class for service {}", serviceName, e);
                    }
                    throw e;
                }
            }
            return false;
        }

        @Override
        @Substitute
        public Spliterator<S> trySplit() {
            return null;
        }

        @Override
        @Substitute
        public long estimateSize() {
            return Long.MAX_VALUE;
        }

        @Override
        @Substitute
        public int characteristics() {
            return NONNULL | IMMUTABLE;
        }

    }

}