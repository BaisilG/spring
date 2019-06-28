/*
 * Copyright 2000-2017 Vaadin Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.vaadin.flow.spring;

import javax.servlet.ServletConfig;
import javax.servlet.ServletContainerInitializer;
import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.ServletException;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.googlecode.gentyref.GenericTypeReflector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.boot.web.servlet.ServletContextInitializer;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.env.Environment;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.core.type.filter.AssignableTypeFilter;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.WebComponentExporter;
import com.vaadin.flow.component.dependency.NpmPackage;
import com.vaadin.flow.function.DeploymentConfiguration;
import com.vaadin.flow.router.HasErrorParameter;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouteAlias;
import com.vaadin.flow.router.RouteConfiguration;
import com.vaadin.flow.server.AmbiguousRouteConfigurationException;
import com.vaadin.flow.server.DeploymentConfigurationFactory;
import com.vaadin.flow.server.InvalidRouteConfigurationException;
import com.vaadin.flow.server.RouteRegistry;
import com.vaadin.flow.server.VaadinServletContext;
import com.vaadin.flow.server.startup.AbstractRouteRegistryInitializer;
import com.vaadin.flow.server.startup.AnnotationValidator;
import com.vaadin.flow.server.startup.ApplicationRouteRegistry;
import com.vaadin.flow.server.startup.DevModeInitializer;
import com.vaadin.flow.server.startup.ServletVerifier;
import com.vaadin.flow.server.startup.WebComponentConfigurationRegistryInitializer;
import com.vaadin.flow.server.startup.WebComponentExporterAwareValidator;
import com.vaadin.flow.server.webcomponent.WebComponentConfigurationRegistry;
import com.vaadin.flow.spring.VaadinScanPackagesRegistrar.VaadinScanPackages;

/**
 * Servlet context initializer for Spring Boot Application.
 * <p>
 * If Java application is used to run Spring Boot then it doesn't run registered
 * {@link ServletContainerInitializer}s (e.g. to scan for {@link Route}
 * annotations in the classpath). This class enables this scanning via Spring so
 * that the functionality which relies on {@link ServletContainerInitializer}
 * works in the same way as in deployable WAR file.
 *
 * @see ServletContainerInitializer
 * @see RouteRegistry
 */
public class VaadinServletContextInitializer
        implements ServletContextInitializer {

    private ApplicationContext appContext;
    private ResourceLoader customLoader;

    private class RouteServletContextListener extends
            AbstractRouteRegistryInitializer implements ServletContextListener {

        @SuppressWarnings("unchecked")
        @Override
        public void contextInitialized(ServletContextEvent event) {
            ApplicationRouteRegistry registry = ApplicationRouteRegistry
                    .getInstance(event.getServletContext());

            getLogger().debug(
                    "Servlet Context initialized. Running route discovering....");

            if (registry.getRegisteredRoutes().isEmpty()) {
                getLogger().debug("There are no discovered routes yet. "
                        + "Start to collect all routes from the classpath...");
                try {
                    List<Class<?>> routeClasses = findByAnnotation(
                            getRoutePackages(), Route.class, RouteAlias.class)
                                    .collect(Collectors.toList());

                    getLogger().debug(
                            "Found {} route classes. Here is the list: {}",
                            routeClasses.size(), routeClasses);

                    Set<Class<? extends Component>> navigationTargets = validateRouteClasses(
                            routeClasses.stream());

                    getLogger().debug(
                            "There are {} navigation targets after filtering route classes: {}",
                            navigationTargets.size(), navigationTargets);

                    RouteConfiguration routeConfiguration = RouteConfiguration
                            .forRegistry(registry);
                    routeConfiguration
                            .update(() -> setAnnotatedRoutes(routeConfiguration,
                                    navigationTargets));
                    registry.setPwaConfigurationClass(
                            validatePwaClass(routeClasses.stream()));
                } catch (InvalidRouteConfigurationException e) {
                    throw new IllegalStateException(e);
                }
            } else {
                getLogger().debug(
                        "Skipped discovery as there was {} routes already in registry",
                        registry.getRegisteredRoutes().size());
            }

        }

        @Override
        public void contextDestroyed(ServletContextEvent sce) {
            // no need to do anything
        }

        private void setAnnotatedRoutes(RouteConfiguration routeConfiguration,
                Set<Class<? extends Component>> routes) {
            routeConfiguration.getHandledRegistry().clean();
            for (Class<? extends Component> navigationTarget : routes) {
                try {
                    routeConfiguration.setAnnotatedRoute(navigationTarget);
                } catch (AmbiguousRouteConfigurationException exception) {
                    if (!handleAmbiguousRoute(routeConfiguration,
                            exception.getConfiguredNavigationTarget(),
                            navigationTarget)) {
                        throw exception;
                    }
                }
            }
        }

        private boolean handleAmbiguousRoute(
                RouteConfiguration routeConfiguration,
                Class<? extends Component> configuredNavigationTarget,
                Class<? extends Component> navigationTarget) {
            if (GenericTypeReflector.isSuperType(navigationTarget,
                    configuredNavigationTarget)) {
                return true;
            } else if (GenericTypeReflector.isSuperType(
                    configuredNavigationTarget, navigationTarget)) {
                routeConfiguration.removeRoute(configuredNavigationTarget);
                routeConfiguration.setAnnotatedRoute(navigationTarget);
                return true;
            }
            return false;
        }

    }

    private class ErrorParameterServletContextListener
            implements ServletContextListener {

        @Override
        @SuppressWarnings("unchecked")
        public void contextInitialized(ServletContextEvent event) {
            ApplicationRouteRegistry registry = ApplicationRouteRegistry
                    .getInstance(event.getServletContext());

            Stream<Class<? extends Component>> hasErrorComponents = findBySuperType(
                    getErrorParameterPackages(), HasErrorParameter.class)
                            .filter(Component.class::isAssignableFrom)
                            .map(clazz -> (Class<? extends Component>) clazz);
            registry.setErrorNavigationTargets(
                    hasErrorComponents.collect(Collectors.toSet()));
        }

        @Override
        public void contextDestroyed(ServletContextEvent sce) {
            // no need to do anything
        }

    }

    private class AnnotationValidatorServletContextListener
            implements ServletContextListener {

        @Override
        public void contextInitialized(ServletContextEvent event) {
            AnnotationValidator annotationValidator = new AnnotationValidator();
            validateAnnotations(annotationValidator, event.getServletContext(),
                    annotationValidator.getAnnotations());

            WebComponentExporterAwareValidator extraValidator = new WebComponentExporterAwareValidator();
            validateAnnotations(extraValidator, event.getServletContext(),
                    extraValidator.getAnnotations());
        }

        @Override
        public void contextDestroyed(ServletContextEvent sce) {
            // no need to do anything
        }

        @SuppressWarnings("unchecked")
        private void validateAnnotations(
                ServletContainerInitializer initializer, ServletContext context,
                List<Class<?>> annotations) {

            Stream<Class<?>> annotatedClasses = findByAnnotation(
                    getVerifiableAnnotationPackages(),
                    annotations.toArray(new Class[annotations.size()]));
            Set<Class<?>> set = annotatedClasses.collect(Collectors.toSet());
            try {
                initializer.onStartup(set, context);
            } catch (ServletException exception) {
                throw new RuntimeException(
                        "Unexpected servlet exception from "
                                + initializer.getClass() + " validator",
                        exception);
            }
        }
    }

    private class DevModeServletContextListener
            implements ServletContextListener {

        @SuppressWarnings({ "unchecked", "rawtypes" })
        @Override
        public void contextInitialized(ServletContextEvent event) {

            ServletRegistrationBean servletRegistrationBean = appContext
                    .getBean("servletRegistrationBean",
                            ServletRegistrationBean.class);

            if (servletRegistrationBean == null) {
                getLogger().warn(
                        "No servlet registration found. DevServer will not be started!");
                return;
            }

            DeploymentConfiguration config = SpringStubServletConfig
                    .createDeploymentConfiguration(event.getServletContext(),
                            servletRegistrationBean, SpringServlet.class, appContext);

            if (config.isCompatibilityMode() || config.isProductionMode()
                    || !config.enableDevServer()) {
                return;
            }

            // Handle classes Route.class, NpmPackage.class,
            // WebComponentExporter.class
            Set<String> allClasses = Collections.singleton("");
            Set<Class<?>> classes = findByAnnotation(allClasses, customLoader,
                    Route.class, NpmPackage.class).collect(Collectors.toSet());

            classes.addAll(findBySuperType(allClasses, customLoader,
                    WebComponentExporter.class).collect(Collectors.toSet()));

            DevModeInitializer.initDevModeHandler(classes,
                    event.getServletContext(), config);
        }

        @Override
        public void contextDestroyed(ServletContextEvent event) {
            // NO-OP
        }
    }

    private class WebComponentServletContextListener
            implements ServletContextListener {

        @Override
        public void contextInitialized(ServletContextEvent event) {
            WebComponentConfigurationRegistry registry = WebComponentConfigurationRegistry
                    .getInstance(new VaadinServletContext(
                            event.getServletContext()));

            if (registry.getConfigurations() == null
                    || registry.getConfigurations().isEmpty()) {
                WebComponentConfigurationRegistryInitializer initializer = new WebComponentConfigurationRegistryInitializer();

                Set<Class<?>> webComponentExporters = findBySuperType(
                        getWebComponentPackages(), WebComponentExporter.class)
                                .collect(Collectors.toSet());

                try {
                    initializer.onStartup(webComponentExporters,
                            event.getServletContext());
                } catch (ServletException e) {
                    throw new RuntimeException(
                            String.format("Failed to initialize %s",
                                    WebComponentConfigurationRegistry.class
                                            .getSimpleName()),
                            e);
                }
            }
        }

        @Override
        public void contextDestroyed(ServletContextEvent sce) {
            // no need to do anything
        }

    }

    /**
     * Creates a new {@link ServletContextInitializer} instance with application
     * {@code context} provided.
     *
     * @param context
     *            the application context
     */
    public VaadinServletContextInitializer(ApplicationContext context) {
        appContext = context;
        String property = appContext.getEnvironment()
                .getProperty("vaadin.blacklisted-packages");
        List<String> blacklist;
        if (property == null) {
            blacklist = Collections.emptyList();
        } else {
            blacklist = Arrays.stream(property.split(",")).map(String::trim)
                    .collect(Collectors.toList());
        }
        customLoader = new CustomResourceLoader(appContext, blacklist);
    }

    @Override
    public void onStartup(ServletContext servletContext)
            throws ServletException {

        // Verify servlet version also for SpringBoot.
        ServletVerifier.verifyServletVersion();

        ApplicationRouteRegistry registry = ApplicationRouteRegistry
                .getInstance(servletContext);
        // If the registry is already initialized then RouteRegistryInitializer
        // has done its job already, skip the custom routes search
        if (registry.getRegisteredRoutes().isEmpty()) {
            /*
             * Don't rely on RouteRegistry.isInitialized() negative return value
             * here because it's not known whether RouteRegistryInitializer has
             * been executed already or not (the order is undefined). Postpone
             * this to the end of context initialization cycle. At this point
             * RouteRegistry is either initialized or it's not initialized
             * because an RouteRegistryInitializer has not been executed (end
             * never will).
             */
            servletContext.addListener(new RouteServletContextListener());
        }

        servletContext.addListener(new ErrorParameterServletContextListener());

        servletContext
                .addListener(new AnnotationValidatorServletContextListener());

        servletContext.addListener(new DevModeServletContextListener());

        // Skip custom web component builders search if registry already
        // initialized
        if (!WebComponentConfigurationRegistry
                .getInstance(new VaadinServletContext(servletContext))
                .hasConfigurations()) {
            servletContext
                    .addListener(new WebComponentServletContextListener());
        }
    }

    private Stream<Class<?>> findByAnnotation(Collection<String> packages,
            Class<? extends Annotation>... annotations) {
        return findByAnnotation(packages, appContext, annotations);
    }

    private Stream<Class<?>> findByAnnotation(Collection<String> packages,
            ResourceLoader loader, Class<? extends Annotation>... annotations) {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(
                false);

        scanner.setResourceLoader(loader);
        Stream.of(annotations).forEach(annotation -> scanner
                .addIncludeFilter(new AnnotationTypeFilter(annotation)));

        return packages.stream().map(scanner::findCandidateComponents)
                .flatMap(set -> set.stream()).map(this::getBeanClass);
    }

    private Stream<Class<?>> findBySuperType(Collection<String> packages,
            Class<?> type) {
        return findBySuperType(packages, appContext, type);
    }

    private Stream<Class<?>> findBySuperType(Collection<String> packages,
            ResourceLoader loader, Class<?> type) {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(
                false);
        scanner.setResourceLoader(loader);
        scanner.addIncludeFilter(new AssignableTypeFilter(type));

        return packages.stream().map(scanner::findCandidateComponents)
                .flatMap(set -> set.stream()).map(this::getBeanClass);
    }

    private Class<?> getBeanClass(BeanDefinition beanDefinition) {
        AbstractBeanDefinition definition = (AbstractBeanDefinition) beanDefinition;
        Class<?> beanClass;
        if (definition.hasBeanClass()) {
            beanClass = definition.getBeanClass();
        } else {
            try {
                beanClass = definition
                        .resolveBeanClass(appContext.getClassLoader());
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e);
            }
        }
        return beanClass;
    }

    private Collection<String> getRoutePackages() {
        return getDefaultPackages();
    }

    private Collection<String> getVerifiableAnnotationPackages() {
        return getDefaultPackages();
    }

    private Collection<String> getWebComponentPackages() {
        return getDefaultPackages();
    }

    private Collection<String> getErrorParameterPackages() {
        return Stream
                .concat(Stream
                        .of(HasErrorParameter.class.getPackage().getName()),
                        getDefaultPackages().stream())
                .collect(Collectors.toSet());
    }

    private List<String> getDefaultPackages() {
        List<String> packagesList = Collections.emptyList();
        if (appContext
                .getBeanNamesForType(VaadinScanPackages.class).length > 0) {
            VaadinScanPackages packages = appContext
                    .getBean(VaadinScanPackages.class);
            packagesList = packages.getScanPackages();

        }
        if (!packagesList.isEmpty()) {
            getLogger().trace(
                    "Using explicitly configured packages for scan Vaadin types at startup {}",
                    packagesList);
        } else if (AutoConfigurationPackages.has(appContext)) {
            packagesList = AutoConfigurationPackages.get(appContext);
        }
        return packagesList;
    }

    /**
     * For NPM we scan all packages. For performance reasons and due to problems
     * with atmosphere we skip known packaged from our resources collection.
     */
    private static class CustomResourceLoader
            extends PathMatchingResourcePatternResolver {

        /**
         * Blacklisted packages that shouldn't be scanned for when scanning all
         * packages.
         */
        private List<String> blackListed = Stream.of("antlr", "cglib",
                "ch/quos/logback", "commons-codec", "commons-fileupload",
                "commons-io", "commons-logging", "com/fasterxml", "com/google",
                "com/h2database", "com/helger",
                "com/vaadin/external/atmosphere", "com/vaadin/webjar", "javax/",
                "junit", "net/bytebuddy", "org/apache", "org/aspectj",
                "org/bouncycastle", "org/dom4j", "org/easymock", "org/hamcrest",
                "org/hibernate", "org/javassist", "org/jboss", "org/jsoup",
                "org/seleniumhq", "org/slf4j", "org/atmosphere",
                "org/springframework", "org/webjars/bowergithub", "org/yaml")
                .collect(Collectors.toList());

        public CustomResourceLoader(ResourceLoader resourceLoader,
                List<String> addedBlacklist) {
            super(resourceLoader);
            blackListed.addAll(addedBlacklist);
        }

        /**
         * Lock used to ensure there's only one update going on at once.
         * <p>
         * The lock is configured to always guarantee a fair ordering.
         */
        private final ReentrantLock lock = new ReentrantLock(true);

        private Map<String, Resource[]> cache = new HashMap<>();

        @Override
        public Resource[] getResources(String locationPattern)
                throws IOException {
            lock.lock();
            try {
                if (cache.containsKey(locationPattern)) {
                    return cache.get(locationPattern);
                }
                Resource[] resources = collectResources(locationPattern);
                cache.put(locationPattern, resources);
                return resources;
            } finally {
                lock.unlock();
            }
        }

        private Resource[] collectResources(String locationPattern)
                throws IOException {
            List<Resource> resourcesList = new ArrayList<>();
            for (Resource resource : super.getResources(locationPattern)) {
                String path = resource.getURL().getPath();
                Optional<String> blacklisted = blackListed.stream()
                        .filter(path::contains).findAny();
                if (!blacklisted.isPresent()) {
                    resourcesList.add(resource);
                }
            }
            return resourcesList.toArray(new Resource[0]);
        }
    }

    /**
     * ServletConfig implementation for getting initial properties for building
     * the DeploymentConfiguration.
     */
    protected static class SpringStubServletConfig implements ServletConfig {

        private final ServletContext context;
        private final ServletRegistrationBean registration;
        private final ApplicationContext appContext;

        /**
         * Constructor.
         *
         * @param context
         *         the ServletContext
         * @param registration
         *         the ServletRegistration for this ServletConfig instance
         */
        private SpringStubServletConfig(ServletContext context,
                ServletRegistrationBean registration,
                ApplicationContext appContext) {
            this.context = context;
            this.registration = registration;
            this.appContext = appContext;
        }

        @Override
        public String getServletName() {
            return registration.getServletName();
        }

        @Override
        public ServletContext getServletContext() {
            return context;
        }

        @SuppressWarnings("unchecked")
        @Override
        public String getInitParameter(String name) {
            Environment env = appContext.getBean(Environment.class);
            String propertyValue = env.getProperty("vaadin." + name);
            if (propertyValue != null) {
                return propertyValue;
            }

            return ((Map<String, String>) registration.getInitParameters())
                    .get(name);
        }

        @SuppressWarnings("unchecked")
        @Override
        public Enumeration<String> getInitParameterNames() {
            Environment env = appContext.getBean(Environment.class);
            // Collect any vaadin.XZY properties from application.properties
            List<String> initParameters = SpringServlet.PROPERTY_NAMES.stream()
                    .filter(name -> env.getProperty("vaadin." + name) != null)
                    .collect(Collectors.toList());
            initParameters.addAll(registration.getInitParameters().keySet());
            return Collections.enumeration(initParameters);
        }

        /**
         * Creates a DeploymentConfiguration.
         *
         * @param context
         *         the ServletContext
         * @param registration
         *         the ServletRegistrationBean to get servlet parameters from
         * @param servletClass
         *         the class to look for properties defined with annotations
         * @return a DeploymentConfiguration instance
         */
        public static DeploymentConfiguration createDeploymentConfiguration(
                ServletContext context, ServletRegistrationBean registration,
                Class<?> servletClass, ApplicationContext appContext) {
            try {
                ServletConfig servletConfig = new SpringStubServletConfig(context,
                        registration, appContext);
                return DeploymentConfigurationFactory
                        .createPropertyDeploymentConfiguration(servletClass,
                                servletConfig);
            } catch (ServletException e) {
                throw new IllegalStateException(String.format(
                        "Failed to get deployment configuration data for servlet with name '%s' and class '%s'",
                        registration.getServletName(), servletClass), e);
            }
        }
    }

    private static Logger getLogger() {
        return LoggerFactory.getLogger(VaadinServletContextInitializer.class);
    }
}