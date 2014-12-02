package org.mockserver.maven;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import org.apache.commons.lang3.StringUtils;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.resolver.ArtifactResolutionRequest;
import org.apache.maven.artifact.resolver.ArtifactResolver;
import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.repository.RepositorySystem;
import org.mockserver.cli.Main;
import org.mockserver.configuration.SystemProperties;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * @author jamesdbloom
 */
@Mojo(name = "runForked", requiresProject = false, threadSafe = false)
public class MockServerRunForkedMojo extends MockServerAbstractMojo {

    private ProcessBuildFactory processBuildFactory = new ProcessBuildFactory();
    /**
     * Get a list of artifacts used by this plugin
     */
    @Parameter(defaultValue = "${plugin.artifacts}", required = true, readonly = true)
    protected List<Artifact> pluginArtifacts;
    /**
     * Used to look up Artifacts in the remote repository.
     */
    @Component
    protected RepositorySystem repositorySystem;
    /**
     * Used to look up Artifacts in the remote repository.
     */
    @Component
    protected ArtifactResolver artifactResolver;

    public static String fileSeparators(String path) {
        StringBuilder ret = new StringBuilder();
        for (char c : path.toCharArray()) {
            if ((c == '/') || (c == '\\')) {
                ret.append(File.separatorChar);
            } else {
                ret.append(c);
            }
        }
        return ret.toString();
    }

    public void execute() throws MojoExecutionException {
        if (skip) {
            getLog().info("Skipping plugin execution");
        } else {
            if (getLog().isInfoEnabled()) {
                getLog().info("mockserver:runForked about to start MockServer on: "
                                + (serverPort != -1 ? " serverPort " + serverPort : "")
                                + (serverSecurePort != -1 ? " serverSecurePort " + serverSecurePort : "")
                                + (proxyPort != -1 ? " proxyPort " + proxyPort : "")
                );
            }
            List<String> arguments = new ArrayList<String>(Arrays.asList(getJavaBin()));
//            arguments.add("-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=5010");
            arguments.add("-Dfile.encoding=UTF-8");
            arguments.add("-Dmockserver.logLevel=" + logLevel);
            arguments.add("-cp");
            String classPath = resolveJarWithDependenciesPath();
            if (dependencies != null && !dependencies.isEmpty()) {
                for (Dependency dependency : dependencies) {
                    classPath += System.getProperty("path.separator");
                    classPath += resolvePluginDependencyJarPath(dependency);
                }
            }
            arguments.add(classPath);
            arguments.add(Main.class.getName());
            if (serverPort != -1) {
                arguments.add("-serverPort");
                arguments.add("" + serverPort);
                SystemProperties.mockServerHttpPort(serverPort);
            }
            if (serverSecurePort != -1) {
                arguments.add("-serverSecurePort");
                arguments.add("" + serverSecurePort);
            }
            if (proxyPort != -1) {
                arguments.add("-proxyPort");
                arguments.add("" + proxyPort);
                SystemProperties.proxyHttpPort(proxyPort);
            }
            getLog().info(" ");
            getLog().info(StringUtils.rightPad("", 72, "-"));
            getLog().info("Running MockServer: " + Joiner.on(" ").join(arguments));
            getLog().info(StringUtils.rightPad("", 72, "-"));
            getLog().info(" ");
            ProcessBuilder processBuilder = processBuildFactory.create(arguments);
            if (pipeLogToConsole) {
                processBuilder.redirectErrorStream(true);
            }
            try {
                processBuilder.start();
            } catch (IOException e) {
                getLog().error("Exception while starting MockServer", e);
            }
            try {
                TimeUnit.SECONDS.sleep((timeout == 0 ? 2 : timeout));
            } catch (InterruptedException e) {
                throw new RuntimeException("Exception while waiting for mock server JVM to start", e);
            }
            InstanceHolder.runInitializationClass(serverPort, createInitializer());
        }

    }

    @VisibleForTesting
    String getJavaBin() {
        String javaBinary = "java";

        File javaHomeDirectory = new File(System.getProperty("java.home"));
        for (String javaExecutable : new String[]{"java", "java.exe"}) {
            File javaExeLocation = new File(javaHomeDirectory, fileSeparators("bin/" + javaExecutable));
            if (javaExeLocation.exists() && javaExeLocation.isFile()) {
                javaBinary = javaExeLocation.getAbsolutePath();
                break;
            }
        }

        return javaBinary;
    }

    @VisibleForTesting
    String resolvePluginDependencyJarPath(Dependency dependency) {
        Artifact dependencyArtifact = repositorySystem.createArtifactWithClassifier(dependency.getGroupId(), dependency.getArtifactId(), dependency.getVersion(), dependency.getType(), dependency.getClassifier());
        artifactResolver.resolve(new ArtifactResolutionRequest().setArtifact(dependencyArtifact));
        return dependencyArtifact.getFile().getAbsolutePath();
    }

    @VisibleForTesting
    String resolveJarWithDependenciesPath() {
        Artifact jarWithDependencies = repositorySystem.createArtifactWithClassifier("org.mock-server", "mockserver-netty", getVersion(), "jar", "jar-with-dependencies");
        artifactResolver.resolve(new ArtifactResolutionRequest().setArtifact(jarWithDependencies));
        return jarWithDependencies.getFile().getAbsolutePath();
    }

    @VisibleForTesting
    String getVersion() {
        String version = "3.8.2";
        try {
            Properties p = new Properties();
            InputStream is = getClass().getResourceAsStream("/META-INF/maven/org.mock-server/mockserver-maven-plugin/pom.properties");
            if (is != null) {
                p.load(is);
                version = p.getProperty("version", "3.8.2");
            }
        } catch (Exception e) {
            // ignore
        }
        getLog().info("Using org.mock-server:mockserver-netty:" + version + ":jar-with-dependencies");
        return version;
    }
}
