/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2020  huangyuhui <huanghongxun2008@126.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.jackhuang.hmcl.plugin.store;

import com.google.gson.JsonParseException;
import org.jackhuang.hmcl.Metadata;
import org.jackhuang.hmcl.plugin.PluginArtifactIdentity;
import org.jackhuang.hmcl.plugin.PluginDependency;
import org.jackhuang.hmcl.plugin.PluginManifest;
import org.jackhuang.hmcl.plugin.PluginVersion;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jackhuang.hmcl.util.io.HttpRequest;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Resolves validated remote registries, checks compatibility, and downloads verified plugin packages atomically.
@NotNullByDefault
public final class PluginStoreManager {
    /// Official HMCL Nex plugin registry.
    public static final String DEFAULT_REGISTRY_URL =
            "https://raw.githubusercontent.com/PCL-Nex-Developer/HMCL-Nex-Plugin-Store/main/plugins.json";

    /// Hard upper bound for any downloaded plugin package.
    private static final long MAX_PACKAGE_BYTES = 512L * 1024L * 1024L;

    /// Maximum UTF-8 bytes accepted for the top-level registry document.
    private static final int MAX_REGISTRY_BYTES = 2 * 1024 * 1024;

    /// Maximum UTF-8 bytes accepted for one plugin repository manifest.
    private static final int MAX_STORE_MANIFEST_BYTES = 4 * 1024 * 1024;

    /// Maximum plugin manifest bytes inspected before an atomic package replacement.
    private static final int MAX_PLUGIN_MANIFEST_BYTES = 1024 * 1024;

    /// Maximum README bytes retained and rendered by the store.
    private static final int MAX_README_BYTES = 2 * 1024 * 1024;

    /// Maximum redirects followed for one store-owned HTTP request.
    private static final int MAX_REDIRECTS = 20;

    /// Extracts the first Java feature number from registry text.
    private static final Pattern JAVA_VERSION_PATTERN = Pattern.compile("(\\d+)");

    /// Currently loaded validated registry.
    private @Nullable PluginStoreRegistry registry;

    /// Registry URL selected by this manager, restored from preferences until a request loads another source.
    private String registryUrl = DEFAULT_REGISTRY_URL;

    /// Known registry URLs displayed by the source selector.
    private final List<String> registryUrls = new ArrayList<>();

    /// Validated repository manifests cached by URL.
    private final Map<String, PluginStoreManifest> manifestCache = new ConcurrentHashMap<>();

    /// Bounded README text cached by validated URL.
    private final Map<String, String> readmeCache = new ConcurrentHashMap<>();

    /// User-owned favorite and registry-source preferences.
    private final PluginStorePreferences preferences;

    /// Creates a store manager configured with the official registry.
    public PluginStoreManager() {
        this(Metadata.HMCL_LOCAL_HOME);
    }

    /// Creates a store manager with isolated preference storage for tests or alternate launcher homes.
    ///
    /// @param localHome launcher-local home
    public PluginStoreManager(Path localHome) {
        preferences = new PluginStorePreferences(localHome);
        registryUrls.add(DEFAULT_REGISTRY_URL);
        for (String customRegistry : preferences.getCustomRegistryUrls()) {
            try {
                validateRemoteUrl(customRegistry, "plugin registry");
                if (!registryUrls.contains(customRegistry)) {
                    registryUrls.add(customRegistry);
                }
            } catch (IOException exception) {
                LOG.warning("Ignoring invalid persisted plugin registry: " + customRegistry, exception);
            }
        }
        String persistedRegistry = preferences.getActiveRegistryUrl();
        try {
            validateRemoteUrl(persistedRegistry, "plugin registry");
            registryUrl = persistedRegistry;
            if (!registryUrls.contains(persistedRegistry)) {
                registryUrls.add(persistedRegistry);
            }
        } catch (IOException exception) {
            LOG.warning("Ignoring invalid active plugin registry: " + persistedRegistry, exception);
        }
    }

    /// Loads and validates a plugin registry from a secure remote URL, then persists it as the active source.
    ///
    /// @param registryUrl registry URL
    /// @throws IOException if transport, parsing, URL policy, or validation fails
    public void loadRegistry(String registryUrl) throws IOException {
        loadRegistryForRequest(registryUrl);
        commitActiveRegistry();
    }

    /// Loads and validates a plugin registry without persisting it as the active source.
    ///
    /// This request-scoped operation lets callers discard stale asynchronous results without allowing an older
    /// request to overwrite the source selected by a newer generation. Call [#commitActiveRegistry()] only after
    /// the loaded result is accepted by the caller.
    ///
    /// @param registryUrl registry URL
    /// @throws IOException if transport, parsing, URL policy, or validation fails
    public void loadRegistryForRequest(String registryUrl) throws IOException {
        validateRemoteUrl(registryUrl, "plugin registry");
        LOG.info("Loading plugin registry from: " + registryUrl);
        try {
            String content = fetchBoundedUtf8(registryUrl, "plugin registry", MAX_REGISTRY_BYTES);
            @Nullable PluginStoreRegistry loadedRegistry = JsonUtils.GSON.fromJson(
                    content,
                    PluginStoreRegistry.class
            );
            if (loadedRegistry == null) {
                throw new IOException("Empty plugin registry: " + registryUrl);
            }
            loadedRegistry.validate();
            for (PluginStoreRegistry.PluginStoreEntry entry : loadedRegistry.getPlugins()) {
                validateRemoteUrl(entry.getManifestUrl(), "plugin manifest");
            }

            registry = loadedRegistry;
            this.registryUrl = registryUrl;
            manifestCache.clear();
            readmeCache.clear();
            LOG.info("Loaded " + loadedRegistry.getPlugins().size() + " plugins from registry");
        } catch (JsonParseException exception) {
            throw new IOException("Failed to parse plugin registry", exception);
        }
    }

    /// Persists the source of the registry most recently loaded successfully by this manager.
    ///
    /// @throws IllegalStateException if this manager has not loaded a registry
    public void commitActiveRegistry() {
        if (registry == null) {
            throw new IllegalStateException("Cannot commit an unloaded plugin registry");
        }
        preferences.setActiveRegistryUrl(registryUrl);
    }

    /// Loads the official plugin registry.
    ///
    /// @throws IOException if loading fails
    public void loadDefaultRegistry() throws IOException {
        loadRegistry(registryUrls.get(0));
    }

    /// Resolves and validates one plugin repository manifest.
    ///
    /// @param pluginId expected plugin ID
    /// @param manifestUrl repository manifest URL
    /// @return validated repository manifest
    /// @throws IOException if transport, parsing, identity, or schema validation fails
    public PluginStoreManifest getPluginManifest(String pluginId, String manifestUrl) throws IOException {
        @Nullable PluginStoreManifest cached = manifestCache.get(manifestUrl);
        if (cached != null) {
            if (!pluginId.equals(cached.getId())) {
                throw new IOException("Cached plugin manifest ID mismatch for " + pluginId);
            }
            return cached;
        }

        validateRemoteUrl(manifestUrl, "plugin manifest");
        LOG.info("Fetching plugin manifest from: " + manifestUrl);
        try {
            String content = fetchBoundedUtf8(manifestUrl, "plugin manifest", MAX_STORE_MANIFEST_BYTES);
            @Nullable PluginStoreManifest manifest = JsonUtils.GSON.fromJson(content, PluginStoreManifest.class);
            if (manifest == null) {
                throw new IOException("Empty plugin manifest: " + manifestUrl);
            }
            manifest.validate(pluginId);
            for (PluginStoreManifest.PluginVersionEntry version : manifest.getVersions()) {
                validateRemoteUrl(version.getPackageUrl(), "plugin package");
            }
            manifestCache.put(manifestUrl, manifest);
            return manifest;
        } catch (JsonParseException exception) {
            throw new IOException("Failed to parse plugin manifest", exception);
        }
    }

    /// Resolves all registry entries, retaining unavailable repositories as partial items.
    ///
    /// @return resolved store items
    public @Unmodifiable List<PluginStoreItem> getStoreItems() {
        @Nullable PluginStoreRegistry currentRegistry = registry;
        if (currentRegistry == null) {
            return List.of();
        }

        List<PluginStoreItem> items = new ArrayList<>();
        for (PluginStoreRegistry.PluginStoreEntry entry : currentRegistry.getPlugins()) {
            try {
                items.add(new PluginStoreItem(
                        entry,
                        getPluginManifest(entry.getId(), entry.getManifestUrl())
                ));
            } catch (IOException exception) {
                LOG.warning("Failed to load plugin manifest: " + entry.getId(), exception);
                items.add(new PluginStoreItem(entry, null));
            }
        }
        return List.copyOf(items);
    }

    /// Resolves a requested version and all transitive plugin dependencies before any package is downloaded.
    ///
    /// This compatibility overload never silently reuses installed dependencies because it has no exact
    /// artifact-bound permission snapshot. Callers that can prove reuse eligibility should use the overload accepting
    /// `reusableInstalledPluginIds`.
    ///
    /// @param pluginId requested root plugin ID
    /// @param requestedVersion exact requested remote version
    /// @param installedManifests installed plugin manifests indexed by ID
    /// @return immutable dependency-first install plan
    /// @throws IOException if metadata is unavailable or the dependency graph cannot be satisfied
    public PluginInstallPlan resolveInstallPlan(
            String pluginId,
            PluginStoreManifest.PluginVersionEntry requestedVersion,
            Map<String, PluginManifest> installedManifests
    ) throws IOException {
        return resolveInstallPlan(pluginId, requestedVersion, installedManifests, Map.of(), Map.of());
    }

    /// Resolves a requested version and all transitive plugin dependencies before any package is downloaded.
    ///
    /// This compatibility overload cannot preserve exact package identities. An empty ID set delegates to the
    /// fail-closed resolver, while a non-empty set is rejected instead of allowing a key-only authorization decision.
    ///
    /// @param pluginId requested root plugin ID
    /// @param requestedVersion exact requested remote version
    /// @param installedManifests installed plugin manifests indexed by ID
    /// @param reusableInstalledPluginIds legacy key-only reusable IDs, which must be empty
    /// @return immutable dependency-first install plan
    /// @throws IOException if metadata is unavailable or the dependency graph cannot be satisfied
    public PluginInstallPlan resolveInstallPlan(
            String pluginId,
            PluginStoreManifest.PluginVersionEntry requestedVersion,
            Map<String, PluginManifest> installedManifests,
            @Unmodifiable Set<String> reusableInstalledPluginIds
    ) throws IOException {
        if (!reusableInstalledPluginIds.isEmpty()) {
            throw new IllegalArgumentException("Reusable plugin IDs cannot authorize reuse without exact artifacts");
        }
        return resolveInstallPlan(pluginId, requestedVersion, installedManifests, Map.of(), Map.of());
    }

    /// Compatibility overload for callers that do not carry a complete installed-artifact snapshot.
    ///
    /// This overload is accepted only when no plugin is installed. Installed state requires the five-argument method
    /// so every update and every reuse decision is bound to one atomic exact-artifact snapshot.
    ///
    /// @param pluginId requested root plugin ID
    /// @param requestedVersion exact requested remote version
    /// @param installedManifests installed plugin manifests indexed by ID
    /// @param reusableInstalledArtifacts legacy partial reusable artifact snapshot
    /// @return immutable dependency-first install plan
    /// @throws IOException if metadata is unavailable or the dependency graph cannot be satisfied
    public PluginInstallPlan resolveInstallPlan(
            String pluginId,
            PluginStoreManifest.PluginVersionEntry requestedVersion,
            Map<String, PluginManifest> installedManifests,
            @Unmodifiable Map<String, PluginArtifactIdentity> reusableInstalledArtifacts
    ) throws IOException {
        if (!installedManifests.isEmpty() || !reusableInstalledArtifacts.isEmpty()) {
            throw new IllegalArgumentException("Installed plugin planning requires complete prior artifact identities");
        }
        return resolveInstallPlan(pluginId, requestedVersion, Map.of(), Map.of(), Map.of());
    }

    /// Resolves a requested version and all transitive dependencies using one complete exact-artifact snapshot.
    ///
    /// Every installed manifest must have one exact prior identity, including disabled or unauthorized artifacts that
    /// will be updated rather than reused. Reusable artifacts must be an exact subset of that same snapshot. Selected
    /// identities are retained so final publication can compare both replacement prior state and reused dependencies.
    ///
    /// @param pluginId requested root plugin ID
    /// @param requestedVersion exact requested remote version
    /// @param installedManifests installed plugin manifests indexed by ID
    /// @param installedArtifactIdentities exact current artifact for every installed manifest
    /// @param reusableInstalledArtifacts exact installed artifacts approved for reuse during planning
    /// @return immutable dependency-first install plan
    /// @throws IOException if metadata is unavailable or the dependency graph cannot be satisfied
    public PluginInstallPlan resolveInstallPlan(
            String pluginId,
            PluginStoreManifest.PluginVersionEntry requestedVersion,
            Map<String, PluginManifest> installedManifests,
            @Unmodifiable Map<String, PluginArtifactIdentity> installedArtifactIdentities,
            @Unmodifiable Map<String, PluginArtifactIdentity> reusableInstalledArtifacts
    ) throws IOException {
        @Nullable PluginStoreRegistry currentRegistry = registry;
        if (currentRegistry == null) {
            throw new IOException("Plugin registry is not loaded");
        }

        @Nullable PluginStoreRegistry.PluginStoreEntry rootStoreEntry = currentRegistry.findPlugin(pluginId);
        if (rootStoreEntry == null) {
            throw new IOException("Plugin is not published by the active registry: " + pluginId);
        }
        PluginStoreManifest rootManifest = getPluginManifest(pluginId, rootStoreEntry.getManifestUrl());
        PluginStoreManifest.PluginVersionEntry rootVersion = requirePublishedVersion(
                pluginId,
                rootManifest,
                requestedVersion
        );
        validateCompatibility(rootVersion);

        @Unmodifiable Map<String, PluginManifest> installed = Map.copyOf(installedManifests);
        @Unmodifiable Map<String, PluginArtifactIdentity> installedArtifacts =
                Map.copyOf(installedArtifactIdentities);
        @Unmodifiable Map<String, PluginArtifactIdentity> reusableInstalled =
                Map.copyOf(reusableInstalledArtifacts);
        if (!installed.keySet().equals(installedArtifacts.keySet())) {
            throw new IllegalArgumentException("Every installed manifest must have one exact prior artifact identity");
        }
        if (!installedArtifacts.keySet().containsAll(reusableInstalled.keySet())) {
            throw new IllegalArgumentException("Reusable artifacts must belong to the installed manifest snapshot");
        }
        for (Map.Entry<String, PluginArtifactIdentity> entry : installedArtifacts.entrySet()) {
            @Nullable PluginManifest installedManifest = installed.get(entry.getKey());
            PluginArtifactIdentity identity = entry.getValue();
            if (!entry.getKey().equals(identity.getPluginId())
                    || installedManifest == null
                    || !installedManifest.getVersion().equals(identity.getVersion())) {
                throw new IllegalArgumentException("Reusable artifact identity does not match the installed snapshot: "
                        + entry.getKey());
            }
        }
        for (Map.Entry<String, PluginArtifactIdentity> entry : reusableInstalled.entrySet()) {
            if (!entry.getValue().equals(installedArtifacts.get(entry.getKey()))) {
                throw new IllegalArgumentException("Reusable artifact differs from the installed snapshot: "
                        + entry.getKey());
            }
        }
        Map<String, PluginInstallPlan.Entry> selected = new LinkedHashMap<>();
        selected.put(pluginId, createRemotePlanEntry(pluginId, rootStoreEntry, rootVersion, installed));
        Map<String, PluginInstallPlan.Entry> solution = new LinkedHashMap<>();
        List<IOException> failures = new ArrayList<>();
        if (!solvePlanSelections(
                pluginId,
                currentRegistry,
                installed,
                reusableInstalled.keySet(),
                selected,
                solution,
                failures
        )) {
            if (!failures.isEmpty()) {
                throw failures.get(failures.size() - 1);
            }
            throw new IOException("Plugin dependency graph cannot be satisfied for " + pluginId);
        }

        validateReverseDependents(installed, solution);
        Map<String, PluginArtifactIdentity> selectedReusableArtifacts = new LinkedHashMap<>();
        Map<String, Optional<PluginArtifactIdentity>> expectedPriorArtifacts = new LinkedHashMap<>();
        for (PluginInstallPlan.Entry entry : solution.values()) {
            if (entry.getAction() == PluginInstallPlan.Action.REUSE) {
                PluginArtifactIdentity identity = reusableInstalled.get(entry.getPluginId());
                if (identity == null) {
                    throw new IllegalStateException("Selected reusable entry has no exact artifact identity: "
                            + entry.getPluginId());
                }
                selectedReusableArtifacts.put(entry.getPluginId(), identity);
            } else if (entry.getAction() == PluginInstallPlan.Action.UPDATE) {
                PluginArtifactIdentity priorIdentity = installedArtifacts.get(entry.getPluginId());
                if (priorIdentity == null) {
                    throw new IllegalStateException("Selected update has no exact prior artifact identity: "
                            + entry.getPluginId());
                }
                expectedPriorArtifacts.put(entry.getPluginId(), Optional.of(priorIdentity));
            } else {
                expectedPriorArtifacts.put(entry.getPluginId(), Optional.empty());
            }
        }
        return new PluginInstallPlan(
                pluginId,
                buildDependencyOrder(pluginId, solution),
                Map.copyOf(selectedReusableArtifacts),
                Map.copyOf(expectedPriorArtifacts)
        );
    }

    /// Searches the complete dependency graph with backtracking so constraints discovered by later siblings can
    /// revise an earlier version choice.
    ///
    /// @param rootPluginId requested root plugin ID
    /// @param currentRegistry active registry
    /// @param installedManifests installed manifests
    /// @param reusableInstalledPluginIds exact installed artifacts approved for reuse
    /// @param selected mutable candidate assignment for the current branch
    /// @param solution successful assignment copied when the graph is complete
    /// @param failures branch diagnostics retained for the final error
    /// @return whether a complete, acyclic assignment was found
    private boolean solvePlanSelections(
            String rootPluginId,
            PluginStoreRegistry currentRegistry,
            Map<String, PluginManifest> installedManifests,
            Set<String> reusableInstalledPluginIds,
            Map<String, PluginInstallPlan.Entry> selected,
            Map<String, PluginInstallPlan.Entry> solution,
            List<IOException> failures
    ) {
        Map<String, List<PluginDependency>> requirements = collectRequirements(
                rootPluginId,
                selected,
                installedManifests
        );
        for (Map.Entry<String, PluginInstallPlan.Entry> assignment : selected.entrySet()) {
            @Nullable List<PluginDependency> constraints = requirements.get(assignment.getKey());
            if (constraints != null && !matchesAll(assignment.getValue().getVersion(), constraints)) {
                failures.add(new IOException("Conflicting dependency constraints for plugin " + assignment.getKey()
                        + ": selected " + assignment.getValue().getVersion() + " does not satisfy "
                        + formatConstraints(constraints)));
                return false;
            }
        }

        @Nullable String unresolvedPluginId = requirements.keySet().stream()
                .filter(candidate -> !selected.containsKey(candidate))
                .findFirst()
                .orElse(null);
        if (unresolvedPluginId == null) {
            try {
                buildDependencyOrder(rootPluginId, selected);
                solution.clear();
                solution.putAll(selected);
                return true;
            } catch (IOException exception) {
                failures.add(exception);
                return false;
            }
        }

        @Unmodifiable List<PluginInstallPlan.Entry> candidates;
        try {
            candidates = getCandidateEntries(
                    unresolvedPluginId,
                    requirements.getOrDefault(unresolvedPluginId, List.of()),
                    currentRegistry,
                    installedManifests,
                    reusableInstalledPluginIds
            );
        } catch (IOException exception) {
            failures.add(exception);
            return false;
        }
        if (candidates.isEmpty()) {
            failures.add(new IOException("No compatible version of dependency " + unresolvedPluginId
                    + " satisfies " + formatConstraints(requirements.getOrDefault(unresolvedPluginId, List.of()))));
            return false;
        }

        for (PluginInstallPlan.Entry candidate : candidates) {
            selected.put(unresolvedPluginId, candidate);
            if (solvePlanSelections(
                    rootPluginId,
                    currentRegistry,
                    installedManifests,
                    reusableInstalledPluginIds,
                    selected,
                    solution,
                    failures
            )) {
                return true;
            }
            selected.remove(unresolvedPluginId);
        }
        return false;
    }

    /// Collects dependency constraints contributed by all candidates selected in the current search branch.
    ///
    /// @param rootPluginId requested root plugin ID
    /// @param selected current candidate assignment
    /// @param installedManifests installed manifests whose out-of-plan reverse constraints must remain valid
    /// @return mutable insertion-ordered constraints indexed by dependency ID
    private static Map<String, List<PluginDependency>> collectRequirements(
            String rootPluginId,
            Map<String, PluginInstallPlan.Entry> selected,
            Map<String, PluginManifest> installedManifests
    ) {
        Map<String, List<PluginDependency>> requirements = new LinkedHashMap<>();
        requirements.put(rootPluginId, new ArrayList<>());
        for (PluginInstallPlan.Entry entry : selected.values()) {
            for (PluginDependency dependency : entry.getDependencies()) {
                requirements.computeIfAbsent(dependency.getId(), ignored -> new ArrayList<>()).add(dependency);
            }
        }

        // Only executable API-v4 plugins can constrain the active dependency graph.
        for (PluginManifest installed : installedManifests.values()) {
            if (installed.getSchemaVersion() < PluginManifest.MIN_EXECUTABLE_SCHEMA_VERSION
                    || requirements.containsKey(installed.getId())) {
                continue;
            }
            for (PluginDependency dependency : installed.getPluginDependencies()) {
                @Nullable List<PluginDependency> dependencyRequirements = requirements.get(dependency.getId());
                if (dependencyRequirements != null) {
                    dependencyRequirements.add(dependency);
                }
            }
        }
        return requirements;
    }

    /// Builds candidate versions in preference order for one dependency under all currently known constraints.
    ///
    /// @param pluginId dependency plugin ID
    /// @param requirements all incoming version requirements
    /// @param currentRegistry active registry
    /// @param installedManifests installed manifests
    /// @param reusableInstalledPluginIds exact installed artifacts approved for reuse
    /// @return immutable candidate list, with an approved compatible installed package first
    /// @throws IOException if remote metadata is required but unavailable
    private @Unmodifiable List<PluginInstallPlan.Entry> getCandidateEntries(
            String pluginId,
            List<PluginDependency> requirements,
            PluginStoreRegistry currentRegistry,
            Map<String, PluginManifest> installedManifests,
            Set<String> reusableInstalledPluginIds
    ) throws IOException {
        List<PluginInstallPlan.Entry> candidates = new ArrayList<>();
        @Nullable PluginManifest installed = installedManifests.get(pluginId);
        boolean installedVersionMatches = installed != null && matchesAll(installed.getVersion(), requirements);
        boolean installedArtifactMayBeReused = installed != null
                && installed.getSchemaVersion() >= PluginManifest.MIN_EXECUTABLE_SCHEMA_VERSION
                && installedVersionMatches
                && reusableInstalledPluginIds.contains(pluginId);
        if (installedArtifactMayBeReused) {
            candidates.add(new PluginInstallPlan.Entry(
                    pluginId,
                    installed.getName(),
                    installed.getVersion(),
                    PluginInstallPlan.Action.REUSE,
                    null,
                    null,
                    installed
            ));
        }

        @Nullable PluginStoreRegistry.PluginStoreEntry storeEntry = currentRegistry.findPlugin(pluginId);
        if (storeEntry == null) {
            if (candidates.isEmpty()) {
                if (installedVersionMatches) {
                    throw new IOException("Installed dependency " + pluginId
                            + " cannot be reused without complete artifact-bound required grants, and the active "
                            + "registry provides no package for a fresh permission review");
                }
                throw new IOException("Missing plugin dependency in active registry: " + pluginId);
            }
            return List.copyOf(candidates);
        }

        PluginStoreManifest manifest = getPluginManifest(pluginId, storeEntry.getManifestUrl());
        for (PluginStoreManifest.PluginVersionEntry version : getCompatibleVersions(manifest)) {
            if (matchesAll(version.getVersion(), requirements)) {
                candidates.add(createRemotePlanEntry(pluginId, storeEntry, version, installedManifests));
            }
        }
        if (candidates.isEmpty() && installedVersionMatches) {
            throw new IOException("Installed dependency " + pluginId
                    + " cannot be reused without complete artifact-bound required grants, and no compatible remote "
                    + "package is available for a fresh permission review");
        }
        return List.copyOf(candidates);
    }

    /// Creates a downloadable plan entry for an exact remote version.
    ///
    /// @param pluginId plugin ID
    /// @param storeEntry parent registry metadata
    /// @param version exact remote version metadata
    /// @param installedManifests installed manifests
    /// @return remote install or update entry
    private static PluginInstallPlan.Entry createRemotePlanEntry(
            String pluginId,
            PluginStoreRegistry.PluginStoreEntry storeEntry,
            PluginStoreManifest.PluginVersionEntry version,
            Map<String, PluginManifest> installedManifests
    ) {
        @Nullable PluginManifest installed = installedManifests.get(pluginId);
        return new PluginInstallPlan.Entry(
                pluginId,
                storeEntry.getName().isBlank() ? pluginId : storeEntry.getName(),
                version.getVersion(),
                installed == null ? PluginInstallPlan.Action.INSTALL : PluginInstallPlan.Action.UPDATE,
                storeEntry,
                version,
                installed
        );
    }

    /// Returns whether a version satisfies every incoming dependency requirement.
    ///
    /// @param version candidate plugin version
    /// @param requirements incoming requirements
    /// @return whether all requirements match
    private static boolean matchesAll(String version, List<PluginDependency> requirements) {
        return requirements.stream().allMatch(requirement -> requirement.matchesVersion(version));
    }

    /// Formats incoming dependency constraints for deterministic diagnostics.
    ///
    /// @param requirements incoming requirements
    /// @return comma-separated constraint expressions
    private static String formatConstraints(List<PluginDependency> requirements) {
        if (requirements.isEmpty()) {
            return "*";
        }
        return requirements.stream().map(PluginDependency::getVersion).distinct().reduce((left, right) -> left
                + ", " + right).orElse("*");
    }

    /// Produces a dependency-first order and rejects cycles in an otherwise complete assignment.
    ///
    /// @param rootPluginId requested root plugin ID
    /// @param selected complete selected entries indexed by ID
    /// @return immutable dependency-first plan order
    /// @throws IOException if the selected dependency graph contains a cycle or incomplete edge
    private static @Unmodifiable List<PluginInstallPlan.Entry> buildDependencyOrder(
            String rootPluginId,
            Map<String, PluginInstallPlan.Entry> selected
    ) throws IOException {
        List<PluginInstallPlan.Entry> order = new ArrayList<>();
        Set<String> visiting = new HashSet<>();
        Set<String> visited = new HashSet<>();
        appendDependencyOrder(rootPluginId, selected, visiting, visited, order);
        return List.copyOf(order);
    }

    /// Appends one selected entry after recursively appending all of its dependencies.
    ///
    /// @param pluginId selected plugin ID
    /// @param selected complete selected entries indexed by ID
    /// @param visiting current recursion stack
    /// @param visited completed plugin IDs
    /// @param order dependency-first output
    /// @throws IOException if a cycle or missing selected dependency is found
    private static void appendDependencyOrder(
            String pluginId,
            Map<String, PluginInstallPlan.Entry> selected,
            Set<String> visiting,
            Set<String> visited,
            List<PluginInstallPlan.Entry> order
    ) throws IOException {
        if (visited.contains(pluginId)) {
            return;
        }
        if (!visiting.add(pluginId)) {
            throw new IOException("Cyclic plugin dependency detected at " + pluginId);
        }
        @Nullable PluginInstallPlan.Entry entry = selected.get(pluginId);
        if (entry == null) {
            throw new IOException("Dependency plan has no selected version for " + pluginId);
        }
        for (PluginDependency dependency : entry.getDependencies()) {
            appendDependencyOrder(dependency.getId(), selected, visiting, visited, order);
        }
        visiting.remove(pluginId);
        visited.add(pluginId);
        order.add(entry);
    }

    /// Verifies that an exact requested version belongs to the resolved repository manifest.
    ///
    /// @param pluginId plugin ID
    /// @param manifest repository manifest
    /// @param requestedVersion requested version metadata
    /// @return canonical version entry from the manifest
    /// @throws IOException if the requested version is not published
    private static PluginStoreManifest.PluginVersionEntry requirePublishedVersion(
            String pluginId,
            PluginStoreManifest manifest,
            PluginStoreManifest.PluginVersionEntry requestedVersion
    ) throws IOException {
        @Nullable PluginStoreManifest.PluginVersionEntry published = manifest.getVersion(requestedVersion.getVersion());
        if (published == null) {
            throw new IOException("Plugin " + pluginId + " does not publish version " + requestedVersion.getVersion());
        }
        return published;
    }

    /// Ensures selected dependency updates do not break installed plugins outside the plan.
    ///
    /// @param installedManifests installed manifests
    /// @param resolved resolved plan entries
    /// @throws IOException if an installed reverse dependent would become invalid
    private static void validateReverseDependents(
            Map<String, PluginManifest> installedManifests,
            Map<String, PluginInstallPlan.Entry> resolved
    ) throws IOException {
        Map<String, String> effectiveVersions = new HashMap<>();
        installedManifests.forEach((id, manifest) -> effectiveVersions.put(id, manifest.getVersion()));
        resolved.forEach((id, entry) -> effectiveVersions.put(id, entry.getVersion()));

        for (PluginManifest installed : installedManifests.values()) {
            if (installed.getSchemaVersion() < PluginManifest.MIN_EXECUTABLE_SCHEMA_VERSION) {
                continue;
            }
            if (resolved.containsKey(installed.getId())
                    && resolved.get(installed.getId()).getAction() != PluginInstallPlan.Action.REUSE) {
                continue;
            }
            for (PluginDependency dependency : installed.getPluginDependencies()) {
                @Nullable String effectiveVersion = effectiveVersions.get(dependency.getId());
                if (effectiveVersion == null || !dependency.matchesVersion(effectiveVersion)) {
                    throw new IOException("Installing this plan would break " + installed.getId()
                            + ": dependency " + dependency.getId() + " " + dependency.getVersion()
                            + " would resolve to " + (effectiveVersion == null ? "missing" : effectiveVersion));
                }
            }
        }
    }

    /// Downloads a package to a temporary file, validates size and SHA-256, then atomically replaces `pluginId.npl`.
    ///
    /// @param pluginId validated plugin ID
    /// @param version remote version metadata
    /// @param targetDirectory installed plugin directory
    /// @return verified installed package path
    /// @throws IOException if compatibility, transport, size, checksum, or replacement fails
    public Path downloadPlugin(
            String pluginId,
            PluginStoreManifest.PluginVersionEntry version,
            Path targetDirectory
    ) throws IOException {
        return downloadPluginToFile(pluginId, version, targetDirectory.resolve(pluginId + ".npl"));
    }

    /// Downloads and fully validates a package in a staging directory without touching installed files.
    ///
    /// The stable checksum prefix makes each selected version deterministic while keeping untrusted version text out
    /// of file names. Callers can download an entire dependency plan here before publishing any package.
    ///
    /// @param pluginId validated plugin ID
    /// @param version selected remote version metadata
    /// @param stagingDirectory isolated staging directory
    /// @return verified staged package path
    /// @throws IOException if compatibility, transport, or package verification fails
    public Path downloadPluginToStaging(
            String pluginId,
            PluginStoreManifest.PluginVersionEntry version,
            Path stagingDirectory
    ) throws IOException {
        String checksumPrefix = version.getSha256().substring(0, 12).toLowerCase(Locale.ROOT);
        return downloadPluginToFile(
                pluginId,
                version,
                stagingDirectory.resolve(pluginId + "-" + checksumPrefix + ".npl")
        );
    }

    /// Downloads and validates a package before atomically publishing it to an explicit target file.
    ///
    /// @param pluginId validated plugin ID
    /// @param version selected remote version metadata
    /// @param targetFile final package path
    /// @return verified target path
    /// @throws IOException if compatibility, transport, size, checksum, metadata, or replacement fails
    private Path downloadPluginToFile(
            String pluginId,
            PluginStoreManifest.PluginVersionEntry version,
            Path targetFile
    ) throws IOException {
        validateCompatibility(version);
        validateRemoteUrl(version.getPackageUrl(), "plugin package");

        Path normalizedTarget = targetFile.toAbsolutePath().normalize();
        @Nullable Path targetDirectory = normalizedTarget.getParent();
        if (targetDirectory == null) {
            throw new IOException("Plugin package target has no parent directory");
        }
        Files.createDirectories(targetDirectory);
        Path temporaryFile = targetDirectory.resolve(
                "." + pluginId + "-" + UUID.randomUUID() + ".download"
        );
        @Nullable Long declaredSize = version.getSize();
        if (declaredSize != null && declaredSize > MAX_PACKAGE_BYTES) {
            throw new IOException("Plugin package exceeds the maximum allowed size");
        }

        MessageDigest digest = createSha256();
        long totalBytes = 0;
        byte[] buffer = new byte[8192];
        LOG.info("Downloading plugin " + pluginId + " v" + version.getVersion()
                + " from " + version.getPackageUrl());

        @Nullable HttpURLConnection connection = null;
        try {
            connection = openValidatedConnection(version.getPackageUrl(), "plugin package");
            int responseCode = connection.getResponseCode();
            if (responseCode / 100 != 2) {
                throw new IOException("Plugin package request failed with HTTP " + responseCode);
            }

            try (InputStream input = new BufferedInputStream(connection.getInputStream());
                 BufferedOutputStream output = new BufferedOutputStream(Files.newOutputStream(temporaryFile))) {
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read == 0) {
                        continue;
                    }
                    totalBytes = Math.addExact(totalBytes, read);
                    if (totalBytes > MAX_PACKAGE_BYTES || declaredSize != null && totalBytes > declaredSize) {
                        throw new IOException("Plugin package exceeds its declared or maximum size");
                    }
                    digest.update(buffer, 0, read);
                    output.write(buffer, 0, read);
                }
            }
        } catch (ArithmeticException exception) {
            throw new IOException("Plugin package size overflow", exception);
        } catch (IOException exception) {
            Files.deleteIfExists(temporaryFile);
            throw exception;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }

        try {
            if (declaredSize != null && totalBytes != declaredSize) {
                throw new IOException("Plugin package size mismatch. Expected " + declaredSize + ", got " + totalBytes);
            }
            String actualHash = toHex(digest.digest());
            if (!actualHash.equalsIgnoreCase(version.getSha256())) {
                throw new IOException("Plugin checksum mismatch. Expected " + version.getSha256()
                        + ", got " + actualHash);
            }
            validateDownloadedPackage(temporaryFile, pluginId, version);
            try {
                Files.move(
                        temporaryFile,
                        normalizedTarget,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporaryFile, normalizedTarget, StandardCopyOption.REPLACE_EXISTING);
            }
            LOG.info("Downloaded and verified plugin package: " + normalizedTarget);
            return normalizedTarget;
        } finally {
            Files.deleteIfExists(temporaryFile);
        }
    }

    /// Validates launcher, Java, and plugin API requirements before downloading a package.
    ///
    /// @param version remote version metadata
    /// @throws IOException if the current runtime is incompatible
    public void validateCompatibility(PluginStoreManifest.PluginVersionEntry version) throws IOException {
        if (version.getPluginApiVersion() != PluginManifest.CURRENT_SCHEMA_VERSION) {
            throw new IOException("HMCL Nex only supports plugin API "
                    + PluginManifest.CURRENT_SCHEMA_VERSION + "; this version uses plugin API "
                    + version.getPluginApiVersion());
        }
        if (!version.matchesLauncherVersion(Metadata.VERSION)) {
            throw new IOException("This plugin requires HMCL launcher version "
                    + version.getLauncherVersion());
        }

        String requiredJava = version.getRequiredJavaVersion();
        if (!requiredJava.isBlank()) {
            Matcher matcher = JAVA_VERSION_PATTERN.matcher(requiredJava);
            if (!matcher.find()) {
                throw new IOException("Invalid requiredJavaVersion: " + requiredJava);
            }
            int requiredFeature;
            try {
                requiredFeature = Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException exception) {
                throw new IOException("Invalid requiredJavaVersion: " + requiredJava, exception);
            }
            if (Runtime.version().feature() < requiredFeature) {
                throw new IOException("This plugin requires Java " + requiredFeature + " or newer");
            }
        }
    }

    /// Returns whether one remote version is compatible with the current launcher and Java runtime.
    ///
    /// @param version remote version metadata
    /// @return compatibility state
    public boolean isCompatible(PluginStoreManifest.PluginVersionEntry version) {
        try {
            validateCompatibility(version);
            return true;
        } catch (IOException exception) {
            return false;
        }
    }

    /// Returns compatible published versions sorted from newest to oldest.
    ///
    /// @param manifest plugin repository manifest
    /// @return immutable compatible version list
    public @Unmodifiable List<PluginStoreManifest.PluginVersionEntry> getCompatibleVersions(
            PluginStoreManifest manifest
    ) {
        return manifest.getVersionsNewestFirst().stream()
                .filter(this::isCompatible)
                .toList();
    }

    /// Returns the newest compatible version from a repository manifest.
    ///
    /// @param manifest plugin repository manifest or `null`
    /// @return newest compatible version or `null`
    public @Nullable PluginStoreManifest.PluginVersionEntry getLatestCompatibleVersion(
            @Nullable PluginStoreManifest manifest
    ) {
        return manifest == null ? null : getCompatibleVersions(manifest).stream().findFirst().orElse(null);
    }

    /// Returns whether a remote version is newer than the installed plugin manifest.
    ///
    /// @param installed installed package manifest or `null`
    /// @param remoteVersion remote version or `null`
    /// @return whether an update is available
    public boolean hasUpdate(
            @Nullable PluginManifest installed,
            @Nullable PluginStoreManifest.PluginVersionEntry remoteVersion
    ) {
        return installed != null
                && remoteVersion != null
                && PluginVersion.compare(
                remoteVersion.getVersion(),
                installed.getVersion()
        ) > 0;
    }

    /// Compares two plugin versions using the shared semantic-version-compatible comparator.
    ///
    /// @param left first version
    /// @param right second version
    /// @return version ordering
    public static int compareVersion(String left, String right) {
        return PluginVersion.compare(left, right);
    }

    /// Returns whether a plugin is marked as a local favorite.
    ///
    /// @param pluginId plugin ID
    /// @return favorite state
    public boolean isFavorite(String pluginId) {
        return preferences.isFavorite(pluginId);
    }

    /// Updates a plugin favorite and persists the change immediately.
    ///
    /// @param pluginId plugin ID
    /// @param favorite desired favorite state
    public void setFavorite(String pluginId, boolean favorite) {
        preferences.setFavorite(pluginId, favorite);
    }

    /// Toggles and returns the resulting favorite state.
    ///
    /// @param pluginId plugin ID
    /// @return resulting favorite state
    public boolean toggleFavorite(String pluginId) {
        boolean favorite = !isFavorite(pluginId);
        setFavorite(pluginId, favorite);
        return favorite;
    }

    /// Returns an immutable snapshot of favorite plugin IDs.
    ///
    /// @return favorite plugin IDs
    public @Unmodifiable Set<String> getFavoritePluginIds() {
        return preferences.getFavoritePluginIds();
    }

    /// Adds a custom secure registry URL to the source selector.
    ///
    /// @param url registry URL
    /// @throws IllegalArgumentException if the URL violates remote transport policy
    public void addCustomRegistry(String url) {
        try {
            validateRemoteUrl(url, "plugin registry");
        } catch (IOException exception) {
            throw new IllegalArgumentException(exception.getMessage(), exception);
        }
        if (!registryUrls.contains(url)) {
            registryUrls.add(url);
        }
        preferences.addCustomRegistryUrl(url);
    }

    /// Adds and loads a registry URL.
    ///
    /// @param url registry URL
    /// @throws IOException if validation or loading fails
    public void setActiveRegistryUrl(String url) throws IOException {
        validateRemoteUrl(url, "plugin registry");
        if (!registryUrls.contains(url)) {
            registryUrls.add(url);
            preferences.addCustomRegistryUrl(url);
        }
        loadRegistry(url);
    }

    /// Returns the registry URL currently selected or loaded by this manager.
    ///
    /// @return registry URL
    public String getRegistryUrl() {
        return registryUrl;
    }

    /// Returns the currently loaded registry.
    ///
    /// @return registry or `null`
    public @Nullable PluginStoreRegistry getRegistry() {
        return registry;
    }

    /// Returns an immutable snapshot of known registry URLs.
    ///
    /// @return registry URLs
    public @Unmodifiable List<String> getRegistryUrls() {
        return List.copyOf(registryUrls);
    }

    /// Downloads and caches a bounded UTF-8 README from the explicit URL in a repository manifest.
    ///
    /// @param manifest plugin repository manifest
    /// @return README Markdown text, or an empty string when no URL is declared
    /// @throws IOException if transport, URL policy, response status, or size validation fails
    public String fetchReadme(PluginStoreManifest manifest) throws IOException {
        String readmeUrl = manifest.getReadmeUrl();
        if (readmeUrl.isBlank()) {
            return "";
        }
        @Nullable String cached = readmeCache.get(readmeUrl);
        if (cached != null) {
            return cached;
        }

        String readme = fetchBoundedUtf8(readmeUrl, "plugin README", MAX_README_BYTES);
        readmeCache.put(readmeUrl, readme);
        return readme;
    }

    /// Clears resolved repository manifests.
    public void clearCache() {
        manifestCache.clear();
        readmeCache.clear();
    }

    /// Enforces HTTPS for remote hosts while allowing loopback HTTP registries used for local development.
    ///
    /// @param url URL to validate
    /// @param purpose value used in diagnostics
    /// @throws IOException if the URL is malformed or insecure
    static void validateRemoteUrl(String url, String purpose) throws IOException {
        final URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException exception) {
            throw new IOException("Invalid " + purpose + " URL: " + url, exception);
        }
        @Nullable String scheme = uri.getScheme();
        @Nullable String host = uri.getHost();
        if (scheme == null || host == null) {
            throw new IOException("Invalid " + purpose + " URL: " + url);
        }
        boolean loopback = host.equalsIgnoreCase("localhost")
                || host.equals("127.0.0.1")
                || host.equals("::1")
                || host.equals("[::1]");
        if (!scheme.equalsIgnoreCase("https") && !(scheme.equalsIgnoreCase("http") && loopback)) {
            throw new IOException("Insecure " + purpose + " URL is not allowed: " + url);
        }
    }

    /// Downloads bounded UTF-8 text and revalidates the final URL after redirects.
    ///
    /// @param url initial remote URL
    /// @param purpose value used in diagnostics
    /// @param maximumBytes maximum accepted response bytes
    /// @return decoded UTF-8 response
    /// @throws IOException if URL policy, transport, status, or size validation fails
    private static String fetchBoundedUtf8(String url, String purpose, int maximumBytes) throws IOException {
        @Nullable HttpURLConnection connection = null;
        try {
            connection = openValidatedConnection(url, purpose);
            int responseCode = connection.getResponseCode();
            if (responseCode / 100 != 2) {
                throw new IOException(purpose + " request failed with HTTP " + responseCode);
            }
            long contentLength = connection.getContentLengthLong();
            if (contentLength > maximumBytes) {
                throw new IOException(purpose + " exceeds the maximum allowed size");
            }
            byte @Unmodifiable [] bytes;
            try (InputStream input = new BufferedInputStream(connection.getInputStream())) {
                bytes = input.readNBytes(maximumBytes + 1);
            }
            if (bytes.length > maximumBytes) {
                throw new IOException(purpose + " exceeds the maximum allowed size");
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /// Opens a GET connection while validating every redirect target before any request is sent to that target.
    ///
    /// @param initialUrl initial request URL
    /// @param purpose value used in diagnostics
    /// @return connected response at the final validated URL
    /// @throws IOException if URL policy, redirect syntax, or redirect depth validation fails
    private static HttpURLConnection openValidatedConnection(String initialUrl, String purpose) throws IOException {
        URI currentUrl = parseRemoteUri(initialUrl, purpose);
        URI firstUrl = currentUrl;
        for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
            validateRemoteUrl(currentUrl.toString(), redirect == 0 ? purpose : purpose + " redirect");
            if (redirect > 0) {
                validateRedirectTarget(firstUrl, currentUrl, purpose);
            }
            HttpURLConnection connection = HttpRequest.GET(currentUrl.toString()).createConnection();
            connection.setInstanceFollowRedirects(false);
            int responseCode = connection.getResponseCode();
            if (!isRedirect(responseCode)) {
                return connection;
            }

            @Nullable String location = connection.getHeaderField("Location");
            connection.disconnect();
            if (location == null || location.isBlank()) {
                throw new IOException(purpose + " redirect has no Location header");
            }
            if (redirect == MAX_REDIRECTS) {
                throw new IOException(purpose + " has too many redirects");
            }
            try {
                currentUrl = currentUrl.resolve(new URI(location));
            } catch (IllegalArgumentException | URISyntaxException exception) {
                throw new IOException("Invalid " + purpose + " redirect URL: " + location, exception);
            }
        }
        throw new IOException(purpose + " has too many redirects");
    }

    /// Enforces that only an explicitly local development request may redirect to loopback HTTP.
    ///
    /// Remote HTTPS chains must remain HTTPS and cannot redirect to a loopback host.
    ///
    /// @param initialUrl first URL in the request chain
    /// @param redirectUrl validated redirect target
    /// @param purpose value used in diagnostics
    /// @throws IOException if a remote chain is downgraded or redirected to loopback
    static void validateRedirectTarget(URI initialUrl, URI redirectUrl, String purpose) throws IOException {
        if (!isLoopbackHttp(initialUrl)
                && (!"https".equalsIgnoreCase(redirectUrl.getScheme())
                || isLoopbackHost(redirectUrl.getHost()))) {
            throw new IOException("Remote " + purpose + " cannot redirect to a local or insecure URL: "
                    + redirectUrl);
        }
    }

    /// Returns whether an HTTP response code represents a redirect followed by the store client.
    ///
    /// @param responseCode HTTP response code
    /// @return whether a Location redirect should be followed
    private static boolean isRedirect(int responseCode) {
        return responseCode >= 300 && responseCode <= 308 && responseCode != 304 && responseCode != 306;
    }

    /// Parses and structurally validates a remote URI before redirect resolution.
    ///
    /// @param url URL text
    /// @param purpose value used in diagnostics
    /// @return parsed URI
    /// @throws IOException if the URL is malformed
    private static URI parseRemoteUri(String url, String purpose) throws IOException {
        try {
            return new URI(url);
        } catch (URISyntaxException exception) {
            throw new IOException("Invalid " + purpose + " URL: " + url, exception);
        }
    }

    /// Returns whether a URI is an explicitly local HTTP endpoint used for plugin-store development.
    ///
    /// @param uri parsed URI
    /// @return whether the URI uses HTTP and a loopback host
    private static boolean isLoopbackHttp(URI uri) {
        return "http".equalsIgnoreCase(uri.getScheme()) && isLoopbackHost(uri.getHost());
    }

    /// Returns whether a nullable URI host is one of the accepted loopback spellings.
    ///
    /// @param host URI host or `null`
    /// @return whether the host is loopback
    private static boolean isLoopbackHost(@Nullable String host) {
        return host != null && (host.equalsIgnoreCase("localhost")
                || host.equals("127.0.0.1")
                || host.equals("::1")
                || host.equals("[::1]"));
    }

    /// Creates a SHA-256 message digest.
    ///
    /// @return digest instance
    /// @throws IOException if SHA-256 is unavailable
    private static MessageDigest createSha256() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IOException("SHA-256 is unavailable", exception);
        }
    }

    /// Validates the internal package manifest before replacing a working installed package.
    ///
    /// @param packageFile verified temporary `.npl` file
    /// @param expectedPluginId plugin ID from the registry
    /// @param expectedVersion complete remote version metadata
    /// @throws IOException if package identity, version, schema, permissions, or dependencies differ from metadata
    private static void validateDownloadedPackage(
            Path packageFile,
            String expectedPluginId,
            PluginStoreManifest.PluginVersionEntry expectedVersion
    ) throws IOException {
        try (ZipFile zipFile = new ZipFile(packageFile.toFile())) {
            @Nullable ZipEntry manifestEntry = zipFile.getEntry("plugin.json");
            if (manifestEntry == null || manifestEntry.isDirectory()) {
                throw new IOException("Downloaded package has no plugin.json");
            }
            if (manifestEntry.getSize() > MAX_PLUGIN_MANIFEST_BYTES) {
                throw new IOException("Downloaded package manifest is too large");
            }

            byte @Unmodifiable [] manifestBytes;
            try (InputStream input = zipFile.getInputStream(manifestEntry)) {
                manifestBytes = input.readNBytes(MAX_PLUGIN_MANIFEST_BYTES + 1);
            }
            if (manifestBytes.length > MAX_PLUGIN_MANIFEST_BYTES) {
                throw new IOException("Downloaded package manifest is too large");
            }

            PluginManifest packageManifest = PluginManifest.fromJson(new java.io.StringReader(
                    new String(manifestBytes, java.nio.charset.StandardCharsets.UTF_8)
            ));
            if (!expectedPluginId.equals(packageManifest.getId())) {
                throw new IOException("Downloaded package ID " + packageManifest.getId()
                        + " does not match registry entry " + expectedPluginId);
            }
            if (!packageManifest.getVersion().equals(expectedVersion.getVersion())) {
                throw new IOException("Downloaded package version " + packageManifest.getVersion()
                        + " does not match selected version " + expectedVersion.getVersion());
            }
            if (packageManifest.getSchemaVersion() != expectedVersion.getPluginApiVersion()) {
                throw new IOException("Downloaded package schemaVersion " + packageManifest.getSchemaVersion()
                        + " does not match pluginApiVersion " + expectedVersion.getPluginApiVersion());
            }
            if (expectedVersion.getPluginApiVersion() >= 3
                    && !new HashSet<>(packageManifest.getPermissions())
                    .equals(new HashSet<>(expectedVersion.getPermissions()))) {
                throw new IOException("Downloaded package permissions do not match selected version metadata");
            }
            if (expectedVersion.getPluginApiVersion() >= 3
                    && !new HashSet<>(packageManifest.getRequiredPermissions())
                    .equals(new HashSet<>(expectedVersion.getRequiredPermissions()))) {
                throw new IOException("Downloaded package requiredPermissions do not match selected version metadata");
            }
            if (!packageManifest.getLauncherVersion().equals(expectedVersion.getLauncherVersion())) {
                throw new IOException("Downloaded package launcherVersion does not match selected version metadata");
            }
            if (expectedVersion.hasAuthoritativeDependencies()
                    && !new HashSet<>(packageManifest.getPluginDependencies())
                    .equals(new HashSet<>(expectedVersion.getDependencies()))) {
                throw new IOException("Downloaded package dependencies do not match selected version metadata");
            }
        }
    }

    /// Converts digest bytes to lower-case hexadecimal text.
    ///
    /// @param bytes digest bytes
    /// @return hexadecimal digest
    private static String toHex(byte @Unmodifiable [] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        return result.toString();
    }
}
