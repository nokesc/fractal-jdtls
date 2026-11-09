// SPDX-License-Identifier: Apache-2.0
package org.fractal.jdtls.endpoint;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Version;

/**
 * The running endpoint (design §11 "Inside JDT LS", "Launching"). Started by the {@link Activator} in Equinox's
 * start-level thread, before JDT LS's application runs:
 *
 * <ol>
 *   <li>reads what the launcher passed ({@link Config}), the token from its pipe ({@link Token}) and the settings
 *       ({@link Settings}), refusing a settings file that changes a fixed setting, the HTTP server's limits unless
 *       they're HTTP mode's ({@link ServerLimits}), and any other LSP transport ({@link Transports});
 *   <li>replaces {@code System.in} and {@code System.out} with two {@link HeapPipe}s, which JDT LS's application
 *       captures when it starts;
 *   <li>listens on {@code 127.0.0.1}, port 0, and writes the port file;
 *   <li>sends {@code initialize} over the pipes, and once JDT LS answers, checks that it holds them
 *       ({@link StreamCheck}).
 * </ol>
 *
 * <p>Any failure ends the process with status 1 and the reason on stderr ({@link Fatal}).
 */
final class Endpoint implements Lifecycle {

    static final String JDTLS_BUNDLE = "org.eclipse.jdt.ls.core";
    static final long SHUTDOWN_GUARD_MINUTES = 5;

    private final Config config;
    private final String jdtlsVersion;
    private final Instant startedAt;
    private final long pid = ProcessHandle.current().pid();
    private final LspClient lsp;
    private final HttpApi http;
    private final CompletableFuture<Void> ready;
    volatile WorktreeOps worktrees;

    private Endpoint(Config config, String jdtlsVersion, LspClient lsp, HttpApi http) {
        this.config = config;
        this.jdtlsVersion = jdtlsVersion;
        this.startedAt = ProcessHandle.current().info().startInstant().orElse(Instant.now());
        this.lsp = lsp;
        this.http = http;
        this.ready = CompletableFuture.allOf(lsp.initialized, lsp.serviceReady);
    }

    static Endpoint start(BundleContext context) throws Exception {
        long t0 = System.nanoTime();
        Config config = Config.fromSystemProperties();
        Log.open(config.log());
        Log.info("starting release " + config.release() + " from " + context.getBundle().getLocation());
        ServerLimits.check();
        Transports.check();
        byte[] token = Token.read(config.tokenFd());
        JsonObject settings = Settings.load(config.settings());
        Description description = Description.load();
        String jdtlsVersion = jdtlsVersion(context);

        HeapPipe toServer = new HeapPipe("endpoint->jdtls");
        HeapPipe fromServer = new HeapPipe("jdtls->endpoint");
        PrintStream serverOut = new PrintStream(fromServer.output, true, StandardCharsets.UTF_8);
        LspClient lsp = new LspClient(toServer.output, fromServer.input, settings);
        Endpoint[] self = new Endpoint[1];
        HttpApi http = new HttpApi(description, new Lifecycle() {
            // Delegates, so the HTTP API can exist before the endpoint does.
            public String release() { return self[0].release(); }
            public String jdtlsVersion() { return self[0].jdtlsVersion(); }
            public long pid() { return self[0].pid(); }
            public long uptimeMs() { return self[0].uptimeMs(); }
            public CompletableFuture<Void> ready() { return self[0].ready(); }
            public void shutdown() { self[0].shutdown(); }
            public void status(JsonObject o) { self[0].status(o); }
        }, token);
        java.util.Arrays.fill(token, (byte) 0);
        Endpoint endpoint = new Endpoint(config, jdtlsVersion, lsp, http);
        self[0] = endpoint;

        Registry registry = new Registry(config.state());
        List<Registry.Worktree> restored = restoreSaved(registry, config.state());
        WorktreeOps worktrees = new WorktreeOps(lsp, registry, endpoint::ready, endpoint::uptimeMs);
        worktrees.register(http);
        endpoint.worktrees = worktrees;
        lsp.workspaceFolders = worktrees::workspaceFolders;
        worktrees.restore(restored);

        System.setIn(toServer.input);
        System.setOut(serverOut);

        int port = http.start();
        PortFile.write(config.portFile(), PortFile.contents(port, endpoint.pid, config.release(), description.apiVersion));
        Log.info("listening on 127.0.0.1:" + port + "; wrote " + config.portFile());

        lsp.initialized.whenComplete((ok, failure) -> {
            if (failure != null) {
                Fatal.exit("JDT LS didn't start with the endpoint as its client", failure);
            }
        });
        List<Path> folders = restored.stream().map(w -> w.root).toList();
        lsp.start(initializeParams(settings, config.release(), folders), () -> {
            StreamCheck.verify(toServer.input, serverOut);
            Log.info(LspPassthrough.coverage(ServedMethods.read(lsp.serverCapabilities)));
            worktrees.initialized();
        });
        Log.info("activator done in " + (System.nanoTime() - t0) / 1_000_000 + " ms");
        return endpoint;
    }

    void stop() {
        Log.info("stopping");
        http.stop();
        PortFile.remove(config.portFile(), pid);
    }

    /**
     * The worktrees the last run registered (design §11 "Worktrees", restart), as JDT LS will get them in
     * {@code initialize}. One whose folder is gone, or that's no longer a worktree root, is left out, dropped from
     * the saved state and reported in the status; JDT LS then deletes its projects as it starts.
     */
    static List<Registry.Worktree> restoreSaved(Registry registry, Path state) throws StartupException {
        List<Registry.Worktree> restored = new ArrayList<>();
        boolean dropped = false;
        for (Path p : Registry.load(state)) {
            String reason = null;
            if (!Files.isDirectory(p)) {
                reason = "its folder is gone";
            } else if (!Files.exists(p.resolve(".git"))) {
                reason = "it's no longer the root of a Git worktree: it has no .git";
            } else if (registry.get(p) != null || registry.named(p.getFileName().toString()) != null) {
                reason = "another saved worktree has the same folder name";
            } else if (Mapping.overlapping(registry, p) != null) {
                reason = "it overlaps the saved worktree " + Mapping.overlapping(registry, p).root + ": nested worktrees are refused";
            }
            if (reason != null) {
                registry.drop(p.toString(), reason);
                Log.info("restart: dropped the saved worktree " + p + ": " + reason);
                dropped = true;
            } else {
                restored.add(registry.add(p));
            }
        }
        if (dropped) {
            try {
                registry.save();
            } catch (IOException e) {
                throw new StartupException("can't save the registered worktrees to " + state + ": " + e + ".");
            }
        }
        Log.info("restart: " + restored.size() + " saved worktrees go to initialize: " + restored.stream().map(w -> w.root.toString()).toList());
        return restored;
    }

    // --- Lifecycle -------------------------------------------------------------------------------

    @Override
    public void status(JsonObject o) {
        WorktreeOps w = worktrees;
        if (w != null) {
            w.status(o);
        }
    }

    @Override
    public String release() {
        return config.release();
    }

    @Override
    public String jdtlsVersion() {
        return jdtlsVersion;
    }

    @Override
    public long pid() {
        return pid;
    }

    @Override
    public long uptimeMs() {
        return Math.max(0, Duration.between(startedAt, Instant.now()).toMillis());
    }

    @Override
    public CompletableFuture<Void> ready() {
        return ready;
    }

    /** LSP {@code shutdown}, so JDT LS saves its workspace, then {@code exit}; JDT LS then ends the process. */
    @Override
    public void shutdown() {
        Thread t = new Thread(() -> {
            try {
                Log.info("shutdown: sending LSP shutdown");
                JsonObject response = lsp.request("shutdown", null).get(SHUTDOWN_GUARD_MINUTES, TimeUnit.MINUTES);
                if (response.has("error")) {
                    Log.info("JDT LS's shutdown failed: " + response.get("error") + "; sending exit anyway");
                } else {
                    Log.info("JDT LS shut down; sending LSP exit");
                }
                lsp.notify("exit", null);
            } catch (TimeoutException e) {
                Fatal.exit("JDT LS didn't answer LSP shutdown within " + SHUTDOWN_GUARD_MINUTES + " minutes", null);
            } catch (Throwable e) {
                Fatal.exit("shutting JDT LS down failed", e);
            }
        }, "fractal-endpoint-shutdown");
        t.setDaemon(true);
        t.start();
    }

    // --- startup ---------------------------------------------------------------------------------

    private static String jdtlsVersion(BundleContext context) throws StartupException {
        for (Bundle bundle : context.getBundles()) {
            if (JDTLS_BUNDLE.equals(bundle.getSymbolicName())) {
                Version v = bundle.getVersion();
                return v.getMajor() + "." + v.getMinor() + "." + v.getMicro();
            }
        }
        throw new StartupException("there's no " + JDTLS_BUNDLE + " bundle: this isn't JDT LS.");
    }

    /**
     * {@code initialize}: no process to watch, the saved worktrees as the folders (JDT LS reads them from
     * {@code initializationOptions.workspaceFolders}, and falls back to {@code -data} without any), the settings, and
     * the client capabilities the endpoint fixes (design §11 "What the endpoint fixes").
     */
    static JsonObject initializeParams(JsonObject settings, String release, List<Path> folders) {
        JsonObject p = new JsonObject();
        p.add("processId", JsonNull.INSTANCE);
        JsonObject client = new JsonObject();
        client.addProperty("name", "fractal-jdtls-endpoint");
        client.addProperty("version", release);
        p.add("clientInfo", client);
        p.add("rootUri", JsonNull.INSTANCE);
        p.add("workspaceFolders", WorktreeOps.folderList(folders));
        p.add("capabilities", Json.parseStrict("""
                {"workspace": {"workspaceFolders": true, "configuration": true, "applyEdit": false,
                               "workspaceEdit": {"documentChanges": true, "resourceOperations": ["create", "rename", "delete"]},
                               "didChangeWatchedFiles": {"dynamicRegistration": false}},
                 "textDocument": {"publishDiagnostics": {}, "synchronization": {"didSave": true},
                                  "rename": {"prepareSupport": true},
                                  "documentSymbol": {"hierarchicalDocumentSymbolSupport": true}},
                 "window": {"workDoneProgress": true}}
                """));
        JsonObject options = new JsonObject();
        options.add("settings", settings);
        JsonArray uris = new JsonArray();
        folders.forEach(f -> uris.add(WorktreeOps.uri(f)));
        options.add("workspaceFolders", uris);
        options.add("extendedClientCapabilities", Json.parseStrict("{\"progressReportProvider\": false}"));
        p.add("initializationOptions", options);
        return p;
    }
}
