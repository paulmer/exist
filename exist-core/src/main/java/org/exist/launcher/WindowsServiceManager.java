/*
 * eXist Open Source Native XML Database
 * Copyright (C) 2001-2017 The eXist Project
 * http://exist-db.org
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301  USA
 */

package org.exist.launcher;

import com.evolvedbinary.j8fu.OptionalUtil;
import com.evolvedbinary.j8fu.lazy.LazyValE;
import com.evolvedbinary.j8fu.tuple.Tuple2;
import net.jcip.annotations.NotThreadSafe;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.exist.util.ConfigurationHelper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.List;

import static com.evolvedbinary.j8fu.Either.Left;
import static com.evolvedbinary.j8fu.Either.Right;
import static com.evolvedbinary.j8fu.tuple.Tuple.Tuple;
import static java.nio.charset.StandardCharsets.UTF_8;

@NotThreadSafe
class WindowsServiceManager implements ServiceManager {

    private static final Logger LOG = LogManager.getLogger(WindowsServiceManager.class);
    private static final String PROCRUN_SRV_EXE = "prunsrv-x86_64.exe";
    private static final String SC_EXE = "sc.exe";

    private static final String SERVICE_NAME = "eXist-db";

    private final Path existHome;
    private final LazyValE<Path, ServiceManagerException> prunsrvExe;

    private enum WindowsServiceState {
        UNINSTALLED,
        RUNNING,
        STOPPED,
        PAUSED
    }

    WindowsServiceManager() {
        this.prunsrvExe = new LazyValE<>(() ->
            OptionalUtil.toRight(() -> new ServiceManagerException("Could not detect EXIST_HOME when trying to find Procrun exe"), ConfigurationHelper.getExistHome())
                .map(base -> base.resolve("bin").resolve(PROCRUN_SRV_EXE))
                .flatMap(exe -> Files.exists(exe) ? Right(exe) : Left(new ServiceManagerException("Could not find Procrun at: " + exe)))
                .flatMap(exe -> Files.isExecutable(exe) ? Right(exe) : Left(new ServiceManagerException("Procrun is not executable at: " + exe)))
        );

        this.existHome = ConfigurationHelper.getExistHome().orElse(Paths.get("."));
    }

    @Override
    public void install() throws ServiceManagerException {
        if (getState() != WindowsServiceState.UNINSTALLED) {
            throw new ServiceManagerException("Service is already installed");
        }

        final Path configFile = ConfigurationHelper.getFromSystemProperty()
                .orElse(existHome.resolve("etc").resolve("conf.xml"));

        final Properties launcherProperties = LauncherWrapper.getLauncherProperties();
        final String minMemory = launcherProperties.getProperty("memory.min", "128") + "m";

        final StringBuilder jvmOptions = new StringBuilder();
        jvmOptions.append("-Dfile.encoding=UTF-8");
        for (final String propertyName : System.getProperties().stringPropertyNames()) {
            if (propertyName.startsWith("exist.") ||
                    propertyName.startsWith("jetty.") ||
                    propertyName.startsWith("log4j.")) {
                final String propertyValue = System.getProperty(propertyName);
                if (propertyValue != null) {
                    jvmOptions
                            .append(";-D").append(propertyName)
                            .append('=')
                            .append(propertyValue);
                }
            }
        }
        final Path exe = prunsrvExe.get();
        final List<String> args = Arrays.asList(exe.toAbsolutePath().toString(), "install", SERVICE_NAME,
                "--DisplayName=" + SERVICE_NAME,
                "--Description=eXist-db NoSQL Database Server",
                "--StdError=auto",
                "--StdOutput=auto",
                "--LogPath=\"" + existHome.resolve("logs").toAbsolutePath().toString() + "\"",
                "--LogPrefix=service",
                "--PidFile=service.pid",
                "--Startup=auto",
                "--Jvm=auto",
                "--Classpath=\"" + existHome.resolve("lib").toAbsolutePath().toString().replace('\\', '/') + "/*\"",
                "--JvmMs=" + minMemory,
                "--StartMode=jvm",
                "--StartClass=org.exist.service.ExistDbDaemon",
                "--StartMethod=start",
                "--StopMode=jvm",
                "--StopClass=org.exist.service.ExistDbDaemon",
                "--StopMethod=stop",
                "--JvmOptions=\"" + jvmOptions + "\"",
                "--StartParams=\"" + configFile.toAbsolutePath().toString() + "\""
        );

        try {
            final Tuple2<Integer, String> execResult = run(args, true);
            final int exitCode = execResult._1;
            final String result = execResult._2;

            if (exitCode != 0) {
                LOG.error("Could not install service, exitCode={}, output='{}'", exitCode, result);
                throw new ServiceManagerException("Could not install service, exitCode=" + exitCode + ", output='" + result + "'");
            }
        } catch (final IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOG.error("Could not install service: " + e.getMessage(), e);
            throw new ServiceManagerException("Could not install service: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean isInstalled() {
        try {
            return getState() != WindowsServiceState.UNINSTALLED;
        } catch (final ServiceManagerException e) {
            LOG.error("Could not determine if service is installed: " + e.getMessage(), e);
            return false;
        }
    }

    @Override
    public void uninstall() throws ServiceManagerException {
        if (getState() == WindowsServiceState.UNINSTALLED) {
            throw new ServiceManagerException("Service is already uninstalled");
        }

        final Path exe = prunsrvExe.get();
        final List<String> args = Arrays.asList(exe.toAbsolutePath().toString(), "delete", SERVICE_NAME);
        try {
            final Tuple2<Integer, String> execResult = run(args, true);
            final int exitCode = execResult._1;
            final String result = execResult._2;

            if (exitCode != 0) {
                LOG.error("Could not uninstall service, exitCode={}, output='{}'", exitCode, result);
                throw new ServiceManagerException("Could not uninstall service, exitCode=" + exitCode + ", output='" + result + "'");
            }
        } catch (final IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOG.error("Could not uninstall service: " + e.getMessage(), e);
            throw new ServiceManagerException("Could not uninstall service: " + e.getMessage(), e);
        }
    }

    @Override
    public void start() throws ServiceManagerException {
        final WindowsServiceState state = getState();
        if (state == WindowsServiceState.RUNNING || state == WindowsServiceState.PAUSED) {
            return;
        }

        if (state == WindowsServiceState.UNINSTALLED) {
            throw new ServiceManagerException("Cannot start service which is not yet installed");
        }

        final Path exe = prunsrvExe.get();
        final List<String> args = Arrays.asList(exe.toAbsolutePath().toString(), "start", SERVICE_NAME);
        try {
            final Tuple2<Integer, String> execResult = run(args, true);
            final int exitCode = execResult._1;
            final String result = execResult._2;

            if (exitCode != 0) {
                LOG.error("Could not start service, exitCode={}, output='{}'", exitCode, result);
                throw new ServiceManagerException("Could not start service, exitCode=" + exitCode + ", output='" + result + "'");
            }
        } catch (final IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOG.error("Could not start service: " + e.getMessage(), e);
            throw new ServiceManagerException("Could not start service: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean isRunning() {
        try {
            return getState() == WindowsServiceState.RUNNING;
        } catch (final ServiceManagerException e) {
            LOG.error("Could not determine if service is running: " + e.getMessage(), e);
            return false;
        }
    }

    @Override
    public void stop() throws ServiceManagerException {
        final WindowsServiceState state = getState();
        if (state == WindowsServiceState.UNINSTALLED) {
            throw new ServiceManagerException("Cannot stop service which is not yet installed");
        }

        if (state != WindowsServiceState.RUNNING) {
            return;
        }

        final Path exe = prunsrvExe.get();
        final List<String> args = Arrays.asList(exe.toAbsolutePath().toString(), "stop", SERVICE_NAME);
        try {
            final Tuple2<Integer, String> execResult = run(args, true);
            final int exitCode = execResult._1;
            final String result = execResult._2;

            if (exitCode != 0) {
                LOG.error("Could not stop service, exitCode={}, output='{}'", exitCode, result);
                throw new ServiceManagerException("Could not stop service, exitCode=" + exitCode + ", output='" + result + "'");
            }
        } catch (final IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOG.error("Could not stop service: " + e.getMessage(), e);
            throw new ServiceManagerException("Could not stop service: " + e.getMessage(), e);
        }
    }

    @Override
    public void showNativeServiceManagementConsole() throws UnsupportedOperationException, ServiceManagerException {
        final List<String> args = Arrays.asList("cmd.exe", "/c", "services.msc");
        final ProcessBuilder pb = new ProcessBuilder(args);
        try {
            pb.start();
        } catch (final IOException e) {
            throw new ServiceManagerException(e.getMessage(), e);
        }
    }

    private WindowsServiceState getState() throws ServiceManagerException {
        try {
            final List<String> args = Arrays.asList(SC_EXE, "query", SERVICE_NAME);
            final Tuple2<Integer, String> execResult = run(args, false);
            final int exitCode = execResult._1;
            final String result = execResult._2;

            if (exitCode == 1060) {
                return WindowsServiceState.UNINSTALLED;
            }
            if (exitCode != 0) {
                throw new ServiceManagerException("Could not query service status, exitCode=" + exitCode + ", output='" + result + "'");
            }

            if (result.contains("STOPPED")) {
                return WindowsServiceState.STOPPED;
            }
            if (result.contains("RUNNING")) {
                return WindowsServiceState.RUNNING;
            }
            if (result.contains("PAUSED")) {
                return WindowsServiceState.PAUSED;
            }

            throw new ServiceManagerException("Could not determine service status, exitCode=" + exitCode + ", output='" + result + "'");

        } catch (final IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new ServiceManagerException(e);
        }
    }

    private Tuple2<Integer, String> run(List<String> args, final boolean elevated) throws IOException, InterruptedException {

        if (elevated) {
            final List<String> elevatedArgs = new ArrayList<>();
            elevatedArgs.add("cmd.exe");
            elevatedArgs.add("/c");
            elevatedArgs.addAll(args);

            args = elevatedArgs;
        }

        if (LOG.isDebugEnabled()) {
            final StringBuilder buf = new StringBuilder("Executing: [");
            for (int i = 0; i < args.size(); i++) {
                buf.append('"');
                buf.append(args.get(i));
                buf.append('"');
                if (i != args.size() - 1) {
                    buf.append(", ");
                }
            }
            buf.append(']');
            LOG.debug(buf.toString());
        }

        final ProcessBuilder pb = new ProcessBuilder(args);
        pb.directory(existHome.toFile());
        pb.redirectErrorStream(true);

        final Process process = pb.start();
        final StringBuilder output = new StringBuilder();
        try (final BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(),
                UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(System.getProperty("line.separator")).append(line);
            }
        }
        final int exitValue = process.waitFor();
        return Tuple(exitValue, output.toString());
    }
}