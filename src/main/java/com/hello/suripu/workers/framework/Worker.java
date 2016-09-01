package com.hello.suripu.workers.framework;


import io.dropwizard.Application;
import io.dropwizard.Configuration;
import io.dropwizard.cli.Cli;
import io.dropwizard.logging.BootstrapLogging;
import io.dropwizard.setup.Bootstrap;
import io.dropwizard.setup.Environment;
import io.dropwizard.util.Generics;
import io.dropwizard.util.JarLocation;

public abstract class Worker<T extends Configuration> {

    private class ShellApplication<T> extends Application {

        @Override
        public void initialize(Bootstrap bootstrap) {

        }

        @Override
        public void run(Configuration configuration, Environment environment) throws Exception {

        }
    }

    static {
        // make sure spinning up Hibernate Validator doesn't yell at us
        BootstrapLogging.bootstrap();
    }

    /**
     * Returns the {@link Class} of the configuration class type parameter.
     *
     * @return the configuration class
     */
    public final Class<T> getConfigurationClass() {
        return Generics.getTypeParameter(getClass(), Configuration.class);
    }

    /**
     * Initializes the service bootstrap.
     *
     * @param bootstrap the service bootstrap
     */
    public abstract void initialize(Bootstrap<T> bootstrap);

    /**
     * Parses command-line arguments and runs the service. Call this method from a {@code public
     * static void main} entry point in your application.
     *
     * @param arguments the command-line arguments
     * @throws Exception if something goes wrong
     */
    public final void run(String[] arguments) throws Exception {
        final Bootstrap<T> bootstrap = new Bootstrap<T>(new ShellApplication<T>());

        initialize(bootstrap);
        final Cli cli = new Cli(new JarLocation(getClass()), bootstrap, System.out, System.err);
        cli.run(arguments);
    }
}
