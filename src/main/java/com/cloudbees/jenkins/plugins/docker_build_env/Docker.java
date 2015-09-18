package com.cloudbees.jenkins.plugins.docker_build_env;

import hudson.EnvVars;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.Computer;
import hudson.model.TaskListener;
import hudson.util.ArgumentListBuilder;
import org.jenkinsci.plugins.docker.commons.credentials.DockerRegistryEndpoint;
import org.jenkinsci.plugins.docker.commons.credentials.DockerServerEndpoint;
import org.jenkinsci.plugins.docker.commons.credentials.KeyMaterial;
import org.jenkinsci.plugins.docker.commons.tools.DockerTool;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * @author <a href="mailto:nicolas.deloof@gmail.com">Nicolas De Loof</a>
 */
public class Docker implements Closeable {

    private static boolean debug = Boolean.getBoolean(Docker.class.getName()+".debug");
    private final Launcher launcher;
    private final TaskListener listener;
    private final String dockerExecutable;
    private final DockerServerEndpoint dockerHost;
    private final DockerRegistryEndpoint registryEndpoint;
    private final boolean verbose;
    private final boolean privileged;
    private final AbstractBuild build;
    private EnvVars envVars;

    public Docker(DockerServerEndpoint dockerHost, String dockerInstallation, String credentialsId, AbstractBuild build, Launcher launcher, TaskListener listener, boolean verbose, boolean privileged) throws IOException, InterruptedException {
        this.dockerHost = dockerHost;
        this.dockerExecutable = DockerTool.getExecutable(dockerInstallation, Computer.currentComputer().getNode(), listener, build.getEnvironment(listener));
        this.registryEndpoint = new DockerRegistryEndpoint(null, credentialsId);
        this.launcher = launcher;
        this.listener = listener;
        this.build = build;
        this.verbose = verbose | debug;
        this.privileged = privileged;
    }


    private KeyMaterial dockerEnv;

    public void setupCredentials(AbstractBuild build) throws IOException, InterruptedException {
        this.dockerEnv = dockerHost.newKeyMaterialFactory(build)
                .plus(   registryEndpoint.newKeyMaterialFactory(build))
                .materialize();
    }


    @Override
    public void close() throws IOException {
        dockerEnv.close();
    }

    public boolean hasImage(String image) throws IOException, InterruptedException {
        ArgumentListBuilder args = dockerCommand()
            .add("inspect", image);
        
        OutputStream out = verbose ? listener.getLogger() : new ByteArrayOutputStream();
        OutputStream err = verbose ? listener.getLogger() : new ByteArrayOutputStream();

        int status = launcher.launch()
                .envs(getEnvVars())
                .cmds(args)
                .stdout(out).stderr(err).quiet(!verbose).join();
        return status == 0;
    }

    private EnvVars getEnvVars() throws IOException, InterruptedException {
        if (envVars == null) {
            envVars = new EnvVars(build.getEnvironment(listener)).overrideAll(dockerEnv.env());
        }
        return envVars;
    }

    public boolean pullImage(String image) throws IOException, InterruptedException {
        ArgumentListBuilder args = dockerCommand()
            .add("pull", image);
        
        OutputStream out = verbose ? listener.getLogger() : new ByteArrayOutputStream();
        OutputStream err = verbose ? listener.getLogger() : new ByteArrayOutputStream();
        int status = launcher.launch()
                .envs(getEnvVars())
                .cmds(args)
                .stdout(out).stderr(err).join();
        return status == 0;
    }


    public void buildImage(FilePath workspace, String dockerfile, String tag) throws IOException, InterruptedException {

        ArgumentListBuilder args = dockerCommand()
            .add("build", "--tag", tag)
            .add("--file", dockerfile)
            .add(workspace.getRemote());

        OutputStream out = listener.getLogger();
        OutputStream err = listener.getLogger();
        int status = launcher.launch()
                .envs(getEnvVars())
                .cmds(args)
                .stdout(out).stderr(err).join();
        if (status != 0) {
            throw new RuntimeException("Failed to build docker image from project Dockerfile");
        }
    }

    public void kill(String container) throws IOException, InterruptedException {
        ArgumentListBuilder args = dockerCommand()
            .add("kill", container);


        listener.getLogger().println("Stopping Docker container after build completion");
        OutputStream out = verbose ? listener.getLogger() : new ByteArrayOutputStream();
        OutputStream err = verbose ? listener.getLogger() : new ByteArrayOutputStream();
        int status = launcher.launch()
                .envs(getEnvVars())
                .cmds(args)
                .stdout(out).stderr(err).quiet(!verbose).join();
        if (status != 0)
            throw new RuntimeException("Failed to stop docker container "+container);

        args = new ArgumentListBuilder()
            .add(dockerExecutable)
            .add("rm", "--force", container);
        status = launcher.launch()
                .envs(getEnvVars())
                .cmds(args)
                .stdout(out).stderr(err).quiet(!verbose).join();
        if (status != 0)
            throw new RuntimeException("Failed to remove docker container "+container);
    }

    public String runDetached(String image, String workdir, Map<String, String> volumes, Map<Integer, Integer> ports, Map<String, String> links, EnvVars environment, Set sensitiveBuildVariables, String... command) throws IOException, InterruptedException {

        String docker0 = getDocker0Ip(launcher, image);


        ArgumentListBuilder args = dockerCommand()
            .add("run", "--tty", "--detach");
        if (privileged) {
            args.add( "--privileged");
        }
        args.add("--workdir", workdir);
        for (Map.Entry<String, String> volume : volumes.entrySet()) {
            args.add("--volume", volume.getKey() + ":" + volume.getValue() + ":rw" );
        }
        for (Map.Entry<Integer, Integer> port : ports.entrySet()) {
            args.add("--publish", port.getKey() + ":" + port.getValue());
        }
        for (Map.Entry<String, String> link : links.entrySet()) {
            args.add("--link", link.getKey() + ":" + link.getValue());
        }
        args.add("--add-host", "dockerhost:"+docker0);

        for (Map.Entry<String, String> e : environment.entrySet()) {
            if ("HOSTNAME".equals(e.getKey())) {
                continue;
            }
            args.add("--env");
            if (sensitiveBuildVariables.contains(e.getKey()))
                args.addMasked(e.getKey()+"="+e.getValue());
            else
                args.add(e.getKey()+"="+e.getValue());
        }
        args.add(image).add(command);

        ByteArrayOutputStream out = new ByteArrayOutputStream();

        int status = launcher.launch()
                .envs(getEnvVars())
                .cmds(args)
                .stdout(out).quiet(!verbose).stderr(listener.getLogger()).join();

        if (status != 0) {
            throw new RuntimeException("Failed to run docker image");
        }
        String container = out.toString("UTF-8").trim();
        return container;
    }

    private String getDocker0Ip(Launcher launcher, String image) throws IOException, InterruptedException {

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int status = launcher.launch()
                .cmds("ifconfig", "docker0")
                .stdout(out)
                .join();

        if (status == 0) {
            final String s = out.toString();
            int i = s.indexOf("inet addr:")+10;
            int j = s.indexOf(' ', i);
            return s.substring(i, j);
        }

        // Docker daemon might be configured with a custom bridge, or maybe we are just running from Windows/OSX
        // with boot2docker ...
        // alternatively, let's run the specified image once to discover gateway IP from the container

        ArgumentListBuilder args = dockerCommand()
                .add("run", "--tty", "--rm")
                .add(image)
                .add("/sbin/ip", "route");

        out = new ByteArrayOutputStream();

        status = launcher.launch()
                .envs(getEnvVars())
                .cmds(args)
                .stdout(out).quiet(!verbose).stderr(listener.getLogger()).join();

        if (status != 0) {
            throw new RuntimeException("Failed to retrieve Docker daemon bridge IP");
        }

        String route = out.toString("UTF-8").trim();

        // equivalent to `awk '/default/ { print $3 }'` but we can't assume awk is available
        String dockerhost = route.substring(route.indexOf("default")) .split(" ")[2];
        return dockerhost;
    }


    public void executeIn(String container, String userId, Launcher.ProcStarter starter) throws IOException, InterruptedException {
        List<String> prefix = dockerCommandArgs();
        prefix.add("exec");
        prefix.add("--tty");
        prefix.add("--user");
        prefix.add(userId);
        prefix.add(container);
        prefix.add("env");

        // Build a list of environment, hidding node's one
        Set<String>  envs = new TreeSet<String>(Arrays.asList(starter.envs()));
        for (String key : Computer.currentComputer().buildEnvironment(listener).keySet()) {
            envs.remove(key);
        }
        prefix.addAll(envs);

        starter.cmds().addAll(0, prefix);
        if (starter.masks() != null) {
            boolean[] masks = new boolean[starter.masks().length + prefix.size()];
            System.arraycopy(starter.masks(), 0, masks, prefix.size(), starter.masks().length);
            starter.masks(masks);
        }

        starter.envs(getEnvVars());
    }

    private ArgumentListBuilder dockerCommand() {
        ArgumentListBuilder args = new ArgumentListBuilder();
        for (String s : dockerCommandArgs()) {
            args.add(s);
        }
        return args;
    }

    private List<String> dockerCommandArgs() {
        List<String> args = new ArrayList<String>();
        args.add(dockerExecutable);
        if (dockerHost.getUri() != null) {
            args.add("-H");
            args.add(dockerHost.getUri());
        }
        return args;
    }
}
