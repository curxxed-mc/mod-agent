package dev.curxxed.agent;

import dev.curxxed.agent.config.Config;
import dev.curxxed.agent.config.ModEntry;
import dev.curxxed.agent.invoke.ModLifecycleInvoker;
import dev.curxxed.agent.mappings.MappingRegistry;
import dev.curxxed.agent.transformer.*;
import dev.curxxed.agent.enums.Environment;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import java.io.*;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.ProtectionDomain;
import java.util.*;
import java.util.concurrent.ConcurrentMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

public class AgentBootstrap {

    private static final String MOD_LIST_PROPERTY  = "lunar.agent.bootstrap.mods";
    private static final String JAR_PATHS_PROPERTY = "lunar.agent.bootstrap.jar.paths";
    private static final String BOOTSTRAPPED_PROPERTY = "lunar.agent.bootstrap.initialized";
    private static final String FORGE_MOD_DESC     = "Lnet/minecraftforge/fml/common/Mod;";

    public static void premain(String args, Instrumentation inst) throws Exception {
        // Launcher JVM fields keep whatever was pasted, often a trailing newline from a terminal copy,
        // and Java passes it through as part of the config path.
        if (args != null) args = args.trim();

        // A child-scoped JAVA_TOOL_OPTIONS agent and the launcher's own JVM field
        // can both request this JAR. Never register the same pipeline twice.
        if (args != null && !args.isEmpty()) {
            synchronized (AgentBootstrap.class) {
                if (Boolean.getBoolean("modagent.premain.initialized")) {
                    AgentLog.log("Duplicate agent invocation ignored; the existing pipeline is retained.");
                    return;
                }
                System.setProperty("modagent.premain.initialized", "true");
            }
        }
        if (Boolean.getBoolean("modagent.badlion.inspectOnly")) {
            BadlionCompatibilityInspection.start(inst);
            return;
        }

        // Clear Lunar bake cache both on start & when quitting.
        clearBakeCache();
        Runtime.getRuntime().addShutdownHook(new Thread(AgentBootstrap::clearBakeCache, "agent-bake-cache-clear"));

        AgentLog.log("premain fired, config: " + args);

        File agentJar = new File(
                AgentBootstrap.class.getProtectionDomain().getCodeSource().getLocation().toURI()
        );
        AgentLog.log("Agent JAR located at: " + agentJar);

        if (args == null || args.isEmpty()) {
            AgentLog.log("No config file specified, nothing to inject.");
            return;
        }

        List<ModEntry> mods = Config.parseConfig(args);
        if (mods.isEmpty()) {
            AgentLog.log("No mods found in config.");
            return;
        }

        for (ModEntry mod : mods) {
            if (mod.getProperty() != null && !mod.getProperty().isEmpty()) {
                System.setProperty(mod.getProperty(), "true");
                AgentLog.log("Set property: " + mod.getProperty());
            }
        }

        String serialized = buildModListProperty(mods);
        if (!serialized.isEmpty()) {
            AgentLog.log("Setting MOD_LIST_PROPERTY = " + serialized);
            System.setProperty(MOD_LIST_PROPERTY, serialized);
            AgentLog.log("Mod list property set: " + serialized);
        }

        StringBuilder jarPaths = new StringBuilder();
        for (ModEntry mod : mods) {
            File f = new File(mod.getJar());
            if (f.exists()) {
                if (jarPaths.length() > 0) jarPaths.append("::");
                jarPaths.append(f.getAbsolutePath());
            }
        }

        if (jarPaths.length() > 0) {
            System.setProperty(JAR_PATHS_PROPERTY, jarPaths.toString());
            AgentLog.log("JAR paths property set: " + jarPaths);
        }

        Set<String> modClassNames = collectModClassNames(mods);
        AgentLog.log("Tracking " + modClassNames.size() + " mod classes for transformation.");
        MappingRegistry mappings = loadMappings();
        AtomicReference<Environment> runtimeEnvironment =
                new AtomicReference<>(Environment.detectRuntimeEnvironment());
        Map<String, String> accessorRenames = buildAccessorRenameMap(mods);
        AgentLog.log("Accessor renames planned: " + accessorRenames.size());
        inst.addTransformer(new MinecraftBootstrapTransformer(mappings, runtimeEnvironment), true);
        inst.addTransformer(new MixinAnnotationPatcher(modClassNames, mappings, runtimeEnvironment::get), true);
        Set<String> remapLoaders = new HashSet<>();
        for (ModEntry mod : mods) {
            if (mod.getRemapLoaders() == null) continue;
            for (String name : mod.getRemapLoaders().split(",")) {
                if (!name.trim().isEmpty()) remapLoaders.add(name.trim());
            }
        }
        inst.addTransformer(new RuntimeRemapper(modClassNames, mappings, runtimeEnvironment::get, remapLoaders), true);
        inst.addTransformer(new EventConstructorPatcher(modClassNames), true);
        inst.addTransformer(new AccessorConflictPatcher(modClassNames, accessorRenames), true);
        AgentLog.log("Transformers registered.");

        final Path mixinPatchDir = createMixinPatchDir();
        for (ModEntry mod : mods) {
            if (mod.getAsm() == null || mod.getAsm().isEmpty()) {
                continue;
            }
            registerAsm(inst, mod, mappings, runtimeEnvironment, modClassNames, hasMixinConfig(mod) ? mixinPatchDir : null);
        }

        BadlionFinalStage.setInstrumentation(inst);
        BadlionFinalStage.install();
        BadlionBootstrapFallback.start(inst, mappings, runtimeEnvironment);

        Thread mixinRegistrar = createMixinRegistrar(inst, agentJar, mods, mixinPatchDir, mappings, runtimeEnvironment, modClassNames, accessorRenames);
        mixinRegistrar.start();
    }

    private static Path createMixinPatchDir() {
        try {
            final Path dir = Files.createTempDirectory("agent-mixin-patch-");
            // Pre-patched mixins classes live in package subdirectories which deleteOnExit can't remove while non-empty.
            // So we remove the whole tree deepest-first instead.
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try (Stream<Path> paths = Files.walk(dir)) {
                    paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
                } catch (IOException ignored) {}
            }));
            return dir;
        } catch (IOException e) {
            AgentLog.log("Failed to create mixin pre-patch dir: " + e);
            return null;
        }
    }

    /// Deletes Lunar's bake.zip class caches.
    private static void clearBakeCache() {
        try {
            Path cacheDir = Paths.get(System.getProperty("user.home"), ".lunarclient", "offline", "multiver", "cache");
            if (Files.exists(cacheDir)) {
                //noinspection resource
                Files.walk(cacheDir)
                        .filter(p -> p.getFileName().toString().equals("bake.zip"))
                        .forEach(p -> {
                            try {
                                Files.delete(p);
                                AgentLog.log("Deleted bake cache: " + p);
                            } catch (IOException e) {
                                AgentLog.log("Failed to delete bake cache: " + p + " -- " + e);
                            }
                        });
            }
        } catch (Exception e) {
            AgentLog.log("Error clearing bake cache: " + e);
        }
    }

    private static boolean hasMixinConfig(ModEntry mod) {
        return mod.getMixin() != null && !mod.getMixin().trim().isEmpty();
    }

    /// Lunar starts 2 mixin runtimes, META_MIXIN and MIXIN.
    /// META_MIXIN: Comes up first and only transforms their own mixin classes.
    /// MIXIN (owned by GenesisClassLoader): Transforms the game.
    /// We wait for the game (MIXIN) one, otherwise we just register mods where game classes
    /// never pass through and nothing would load.
    private static ClassLoader waitForGameMixinLoader(Instrumentation inst) throws InterruptedException {
        ClassLoader metaLoader = null;
        long metaSeen = 0L;

        while (true) {
            for (Class<?> c : inst.getAllLoadedClasses()) {
                if (!"org.spongepowered.asm.mixin.MixinEnvironment".equals(c.getName())) continue;
                final ClassLoader loader = c.getClassLoader();
                if (loader == null) continue;
                if (!String.valueOf(loader).contains("META_MIXIN")) return loader;
                if (metaLoader == null) {
                    metaLoader = loader;
                    metaSeen = System.currentTimeMillis();
                    AgentLog.log("Skipping meta Mixin runtime " + loader + ", waiting for the game runtime...");
                }
            }

            if (metaLoader != null && System.currentTimeMillis() - metaSeen > 30_000L) {
                AgentLog.log("No game Mixin runtime appeared, falling back to " + metaLoader);
                return metaLoader;
            }
            Thread.sleep(50);
        }
    }

    private static Thread createMixinRegistrar(Instrumentation inst, File agentJar, List<ModEntry> mods, Path patchDir, MappingRegistry mappings,
                                               AtomicReference<Environment> runtimeEnvironment, Set<String> modClassNames, Map<String, String> accessorRenames) {
        Thread mixinRegistrar = new Thread(() -> {
            try {
                AgentLog.log("Waiting for MixinEnvironment...");
                final ClassLoader mixinLoader = waitForGameMixinLoader(inst);
                AgentLog.log("Found Mixin loader: " + mixinLoader);

                // TODO: This will likely break if the loader doesn't expose it. We might need to fix this later, with a java 1.8 compatible fix.
                Method addURL;
                try {
                    addURL = mixinLoader.getClass().getMethod("addURL", URL.class); // Exposed by loader
                } catch (NoSuchMethodException e) {
                    addURL = URLClassLoader.class.getDeclaredMethod("addURL", URL.class);
                    addURL.setAccessible(true);
                }
                Class<?> mixins  = Class.forName("org.spongepowered.asm.mixin.Mixins", true, mixinLoader);
                Method addConfig = mixins.getMethod("addConfiguration", String.class);

                final Map<String, byte[]> patchedClasses = new HashMap<>();
                if (patchDir != null) {
                    for (ModEntry mod : mods) {
                        if (hasMixinConfig(mod)) {
                            prePatchMixinMod(mod, patchDir, mappings, runtimeEnvironment.get(), modClassNames, accessorRenames, mixinLoader, patchedClasses);
                        }
                    }
                    try (JarFile agentJf = new JarFile(agentJar)) {
                        JarEntry e = agentJf.getJarEntry("mixins.agent.json");
                        if (e != null) {
                            try (InputStream is = agentJf.getInputStream(e)) {
                                String json = new String(toByteArray(is), StandardCharsets.UTF_8);
                                writePatchFile(patchDir, "mixins.agent.json", stripRefmapField(json).getBytes(StandardCharsets.UTF_8));
                            }
                        }
                    } catch (Exception ignored) {}

                    // The pre-patch dir must go before the mod jar so Mixin reads the patched copies.
                    addURL.invoke(mixinLoader, patchDir.toUri().toURL());
                    AgentLog.log("Pre-patch dir added first to " + mixinLoader + ": " + patchDir);
                }
                addURL.invoke(mixinLoader, agentJar.toURI().toURL()); // TODO: This might not work properly on some java versions
                AgentLog.log("Agent JAR added to " + mixinLoader);

                // Mixin resolves the config json and mixin classes as resources of its own loader,
                // so the mod JAR has to be visible there or the config is not found.
                for (ModEntry mod : mods) {
                    if (!hasMixinConfig(mod)) continue;
                    final File jarFile = new File(mod.getJar());
                    if (!jarFile.isFile()) {
                        AgentLog.log("Mixin mod JAR not found, skipping: " + mod.getJar());
                        continue;
                    }
                    addURL.invoke(mixinLoader, jarFile.toURI().toURL());
                    AgentLog.log("Mod JAR added to " + mixinLoader + ": " + jarFile.getName());
                }

                // An ASM entry for the same jar may have already preloaded the unpatched bytes.
                if (!patchedClasses.isEmpty() && isGenesisLoader(mixinLoader)) {
                    replaceGenesisClassCache(mixinLoader, patchedClasses);
                }

                /// Mixins.
                for (ModEntry mod : mods) {
                    if (!hasMixinConfig(mod)) continue;
                    if (!new File(mod.getJar()).isFile()) continue;
                    try {
                        addConfig.invoke(null, mod.getMixin());
                        AgentLog.log("Mixin config registered: " + mod.getMixin());
                    } catch (InvocationTargetException e) {
                        AgentLog.log("Failed to register mixin config " + mod.getMixin() + ": " + e.getCause());
                    }
                }
            } catch (Exception e) {
                AgentLog.log("Error in mixin registrar: " + e);
                e.printStackTrace();
            }
        });
        mixinRegistrar.setDaemon(true);
        return mixinRegistrar;
    }

    /// Mixin reads mixin classes and its config with getResourceAsStream, so ClassFileTransformers never see them.
    /// The remap, accessor & annotation passes run here, and the config loses its refmap since Ichor refuses
    /// to serve any resource named like one.
    private static void prePatchMixinMod(ModEntry mod, Path patchDir, MappingRegistry mappings, Environment environment, Set<String> modClassNames,
                                         Map<String, String> accessorRenames, ClassLoader mixinLoader, Map<String, byte[]> patchedClasses) {
        final File jarFile = new File(mod.getJar());
        if (!jarFile.isFile()) return;

        try (JarFile jf = new JarFile(jarFile)) {
            final JarEntry jsonEntry = jf.getJarEntry(mod.getMixin());
            if (jsonEntry == null) {
                AgentLog.log("Mixin config not found in JAR: " + mod.getMixin() + " (" + jarFile.getName() + ")");
                return;
            }

            try (InputStream is = jf.getInputStream(jsonEntry)) {
                final String json = new String(toByteArray(is), StandardCharsets.UTF_8);
                writePatchFile(patchDir, mod.getMixin(), stripRefmapField(json).getBytes(StandardCharsets.UTF_8));
                AgentLog.log("Stripped refmap from mixin config: " + mod.getMixin());
            }

            // Accessor interfaces are not mixin-listed classes, but Mixin reads them the same way.
            // Unpatched, their implementations would miss the required accessor renames.
            final Set<String> classPaths = new LinkedHashSet<>(resolveMixinClassPaths(jf, mod.getMixin()));
            for (String renameKey : accessorRenames.keySet()) {
                final String owner = renameKey.substring(0, renameKey.indexOf('\n'));
                if (modClassNames.contains(owner)) {
                    classPaths.add(owner + ".class");
                }
            }

            for (String classPath : classPaths) {
                final JarEntry classEntry = jf.getJarEntry(classPath);
                if (classEntry == null) continue;
                try (InputStream is = jf.getInputStream(classEntry)) {
                    final byte[] processed = prePatchMixinClass(toByteArray(is), mappings, environment, modClassNames, accessorRenames, mixinLoader);
                    writePatchFile(patchDir, classPath, processed);
                    patchedClasses.put(classPath.substring(0, classPath.length() - 6).replace('/', '.'), processed);
                } catch (Exception e) {
                    AgentLog.log("Failed to pre-patch mixin class " + classPath + ": " + e);
                }
            }
            AgentLog.log("Pre-patched " + classPaths.size() + " mixin classes for " + mod.getMixin());
        } catch (IOException e) {
            AgentLog.log("Failed to pre-patch mixin mod: " + mod.getJar() + " -- " + e);
        }
    }

    private static void writePatchFile(Path patchDir, String relativePath, byte[] bytes) throws IOException {
        final Path out = patchDir.resolve(relativePath);
        if (out.getParent() != null) Files.createDirectories(out.getParent());
        Files.write(out, bytes);
    }

    private static byte[] prePatchMixinClass(byte[] bytes,
                                             MappingRegistry mappings,
                                             Environment runtimeEnvironment,
                                             Set<String> modClassNames,
                                             Map<String, String> accessorRenames,
                                             ClassLoader loader) {
        bytes = RuntimeRemapper.remapBytes(
                bytes,
                mappings,
                runtimeEnvironment,
                modClassNames,
                loader
        );

        bytes = injectExplicitAccessorValues(bytes);

        if (!accessorRenames.isEmpty()) {
            bytes = AccessorConflictPatcher.applyRenames(bytes, accessorRenames);
        }

        bytes = MixinAnnotationPatcher.apply(bytes, mappings, runtimeEnvironment);
        return bytes;
    }

    private static byte[] injectExplicitAccessorValues(byte[] bytes) {
        final String ACCESSOR = "Lorg/spongepowered/asm/mixin/gen/Accessor;";
        final String INVOKER  = "Lorg/spongepowered/asm/mixin/gen/Invoker;";

        // Quick pre-scan to avoid a full ASM pass on classes that have no accessors/invokers.
        if (!containsUtf8(bytes, "Accessor") && !containsUtf8(bytes, "Invoker")) return bytes;

        ClassReader cr = new ClassReader(bytes);
        ClassWriter cw = new ClassWriter(cr, 0);

        cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9,
                        super.visitMethod(access, name, descriptor, signature, exceptions)) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                        AnnotationVisitor av = super.visitAnnotation(desc, visible);
                        if (ACCESSOR.equals(desc)) {
                            String target = inflectAccessor(name);
                            if (target != null) {
                                AgentLog.log("Injecting @Accessor value=\"" + target + "\" for method: " + name);
                                return new ValueInjectingAnnotationVisitor(av, target);
                            }
                        } else if (INVOKER.equals(desc)) {
                            String target = inflectInvoker(name);
                            if (target != null) {
                                AgentLog.log("Injecting @Invoker value=\"" + target + "\" for method: " + name);
                                return new ValueInjectingAnnotationVisitor(av, target);
                            }
                        }
                        return av;
                    }
                };
            }
        }, 0);

        return cw.toByteArray();
    }

    // Injects value= into an @Accessor or @Invoker annotation only if the mod author
    // didn't already specify one explicitly. If they did, we leave it alone.
    private static class ValueInjectingAnnotationVisitor extends AnnotationVisitor {
        private final String targetName;
        private boolean sawValue = false;

        ValueInjectingAnnotationVisitor(AnnotationVisitor av, String targetName) {
            super(Opcodes.ASM9, av);
            this.targetName = targetName;
        }

        @Override
        public void visit(String name, Object value) {
            if ("value".equals(name)) sawValue = true;
            super.visit(name, value);
        }

        @Override
        public void visitEnd() {
            if (!sawValue) super.visit("value", targetName);
            super.visitEnd();
        }
    }

    // Inflects the target field name from an @Accessor method name.
    // Strips get/set/is prefix and lowercases the first remaining character.
    // Returns null if the method name doesn't match any known prefix
    // (in which case the mod author must have provided an explicit value= themselves).
    private static String inflectAccessor(String methodName) {
        for (String prefix : new String[]{"get", "set", "is"}) {
            if (methodName.startsWith(prefix) && methodName.length() > prefix.length()) {
                String s = methodName.substring(prefix.length());
                return Character.toLowerCase(s.charAt(0)) + s.substring(1);
            }
        }
        return null;
    }

    // Inflects the target method name from an @Invoker method name.
    // Strips call/invoke prefix and lowercases the first remaining character.
    private static String inflectInvoker(String methodName) {
        for (String prefix : new String[]{"call", "invoke"}) {
            if (methodName.startsWith(prefix) && methodName.length() > prefix.length()) {
                String s = methodName.substring(prefix.length());
                return Character.toLowerCase(s.charAt(0)) + s.substring(1);
            }
        }
        return null;
    }

    // Parses the mixin JSON from inside the JAR to get the full list of mixin class resource paths.
    private static List<String> resolveMixinClassPaths(JarFile jf, String mixinJsonName) {
        List<String> result = new ArrayList<>();
        java.util.jar.JarEntry entry = jf.getJarEntry(mixinJsonName);
        if (entry == null) return result;
        try (InputStream is = jf.getInputStream(entry)) {
            String json    = new String(toByteArray(is), StandardCharsets.UTF_8);
            String pkg     = extractJsonString(json, "package");
            String pkgPath = (pkg != null) ? pkg.replace('.', '/') : "";
            for (String arrayKey : new String[]{"mixins", "client", "server"}) {
                for (String name : extractJsonStringArray(json, arrayKey)) {
                    // Class names in mixin JSON arrays may use dots as package separators
                    // (e.g. "entity.MixinEntityPlayerSP") — convert to slashes for JAR paths.
                    String namePath = name.replace('.', '/');
                    result.add((pkgPath.isEmpty() ? "" : pkgPath + "/") + namePath + ".class");
                }
            }
        } catch (IOException e) {
            AgentLog.log("Failed to resolve mixin class paths from: " + mixinJsonName + " -- " + e);
        }
        return result;
    }

    private static Map<String, String> buildAccessorRenameMap(List<ModEntry> mods) {
        Map<String, String> renames = new HashMap<>();
        final String ACCESSOR = "Lorg/spongepowered/asm/mixin/gen/Accessor;";
        final String INVOKER  = "Lorg/spongepowered/asm/mixin/gen/Invoker;";
        final String MIXIN    = "Lorg/spongepowered/asm/mixin/Mixin;";

        for (ModEntry mod : mods) {
            File f = new File(mod.getJar());
            if (!f.exists()) continue;
            String prefix = deriveModPrefix(mod.getMixin());

            try (JarFile jf = new JarFile(f)) {
                for (java.util.jar.JarEntry entry : Collections.list(jf.entries())) {
                    if (!entry.getName().endsWith(".class")) continue;
                    try (InputStream is = jf.getInputStream(entry)) {
                        byte[] bytes       = toByteArray(is);
                        String internalName = entry.getName().replace(".class", "");
                        org.objectweb.asm.ClassReader cr = new org.objectweb.asm.ClassReader(bytes);
                        boolean[] isMixin = {false};
                        cr.accept(new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
                            @Override
                            public org.objectweb.asm.AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                                if (MIXIN.equals(desc)) isMixin[0] = true;
                                return null;
                            }
                        }, org.objectweb.asm.ClassReader.SKIP_CODE | org.objectweb.asm.ClassReader.SKIP_FRAMES);

                        if (!isMixin[0]) continue;

                        cr.accept(new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
                            @Override
                            public org.objectweb.asm.MethodVisitor visitMethod(
                                    int access, String name, String descriptor, String sig, String[] ex) {
                                return new org.objectweb.asm.MethodVisitor(org.objectweb.asm.Opcodes.ASM9) {
                                    @Override
                                    public org.objectweb.asm.AnnotationVisitor visitAnnotation(String desc, boolean v) {
                                        if (!ACCESSOR.equals(desc) && !INVOKER.equals(desc)) return null;
                                        String newName = name + "_" + prefix;

                                        String interfaceKey = internalName + "\n" + name + "\n" + descriptor;
                                        renames.put(interfaceKey, newName);
                                        AgentLog.log("Accessor rename planned: "
                                                + name + " → " + newName + " in " + internalName);

                                        return null;
                                    }
                                };
                            }
                        }, org.objectweb.asm.ClassReader.SKIP_CODE | org.objectweb.asm.ClassReader.SKIP_FRAMES);

                    } catch (Exception ignored) { /* single class scan failure is not fatal */ }
                }
            } catch (IOException e) {
                AgentLog.log("Failed to scan JAR for accessors: " + mod.getJar() + " -- " + e);
            }
        }
        return renames;
    }


    static String deriveModPrefix(String mixinConfig) {
        if (mixinConfig == null || mixinConfig.isEmpty()) return "mod";
        String s = mixinConfig;
        if (s.startsWith("mixins.")) s = s.substring("mixins.".length());
        if (s.endsWith(".json"))     s = s.substring(0, s.length() - ".json".length());
        int dot = s.indexOf('.');
        return (dot > 0 ? s.substring(0, dot) : s).replaceAll("[^a-zA-Z0-9_]", "_");
    }

    // Strips the "refmap": "..." field from a mixin JSON string in all its valid forms.
    private static String stripRefmapField(String json) {
        String r = json.replaceAll(",\\s*\"refmap\"\\s*:\\s*\"[^\"]*\"", "");
        r = r.replaceAll("\"refmap\"\\s*:\\s*\"[^\"]*\"\\s*,", "");
        r = r.replaceAll("\"refmap\"\\s*:\\s*\"[^\"]*\"", "");
        return r;
    }

    private static boolean containsUtf8(byte[] bytes, String needle) {
        byte[] n = needle.getBytes(StandardCharsets.UTF_8);
        outer:
        for (int i = 0; i <= bytes.length - n.length; i++) {
            for (int j = 0; j < n.length; j++) {
                if (bytes[i + j] != n[j]) continue outer;
            }
            return true;
        }
        return false;
    }

    public static void agentmain(String args, Instrumentation inst) throws Exception {
        premain(args, inst);
    }

    public static void bootstrapLoadedMods(ClassLoader parentLoader) {
        synchronized (AgentBootstrap.class) {
            if (Boolean.getBoolean(BOOTSTRAPPED_PROPERTY)) return;
            System.setProperty(BOOTSTRAPPED_PROPERTY, "true");
        }

        String modList = System.getProperty(MOD_LIST_PROPERTY);
        AgentLog.log("bootstrap property = " + System.getProperty(MOD_LIST_PROPERTY));
        if (modList == null || modList.isEmpty()) {
            AgentLog.log("No mods to bootstrap.");
            return;
        }

        AgentLog.log("Minecraft startGame reached, bootstrapping mods...");

        ClassLoader modLoader = buildModLoader(parentLoader);
        File configDir = deriveConfigDir();

        for (String entry : modList.split(",")) {
            String[] parts = entry.split("\\|", -1);
            if (parts.length < 1 || parts[0].isEmpty()) continue;

            String className = parts[0];
            String property = parts.length > 1 ? parts[1] : "";

            try {
                if (parts.length > 2 && "static".equals(parts[2])) {
                    if (property.isEmpty() || Boolean.getBoolean(property)) {
                        initStaticMod(className, modLoader);
                    }
                } else {
                    initMod(className, property, modLoader, configDir);
                }
            } catch (Throwable t) {
                AgentLog.log("Failed to init mod: " + className + " -- " + t);
                t.printStackTrace();
            }
        }
    }

    private static ClassLoader buildModLoader(ClassLoader parent) {
        AgentLog.log("buildModLoader called, jarPaths: "
                + System.getProperty(JAR_PATHS_PROPERTY));
        String jarPathsProp = System.getProperty(JAR_PATHS_PROPERTY);
        if (jarPathsProp == null || jarPathsProp.isEmpty()) {
            AgentLog.log("No JAR paths registered, mod loading will fail.");
            return parent;
        }

        try {
            String[] paths = jarPathsProp.split("::");
            URL[] urls = new URL[paths.length];
            for (int i = 0; i < paths.length; i++) {
                urls[i] = new File(paths[i]).toURI().toURL();
                AgentLog.log("Mod loader URL: " + urls[i]);
            }
            return new URLClassLoader(urls, parent);
        } catch (Exception e) {
            AgentLog.log("Failed to build mod loader: " + e);
            return parent;
        }
    }

    private static File deriveConfigDir() {
        String jarPaths = System.getProperty(JAR_PATHS_PROPERTY);
        if (jarPaths != null && !jarPaths.isEmpty()) {
            File parent = new File(jarPaths.split("::")[0]).getParentFile();
            if (parent != null) {
                File cfg = new File(parent, "config");
                //noinspection ResultOfMethodCallIgnored
                cfg.mkdirs();
                return cfg;
            }
        }
        File fallback = new File(System.getProperty("user.home"),
                ".lunarclient" + File.separator + "offline"
                        + File.separator + "multiver" + File.separator + "config");
        //noinspection ResultOfMethodCallIgnored
        fallback.mkdirs();
        return fallback;
    }

    static void initStaticMod(String className, ClassLoader loader) throws ReflectiveOperationException {
        Class<?> type = Class.forName(className.replace('/', '.'), true, loader);
        Method init = type.getMethod("init");
        if (!java.lang.reflect.Modifier.isStatic(init.getModifiers())) {
            throw new IllegalArgumentException("Non-Forge entrypoint init() must be static: " + className);
        }
        Method preInit = null;
        try {
            preInit = type.getMethod("preInit");
            if (!java.lang.reflect.Modifier.isStatic(preInit.getModifiers())) {
                throw new IllegalArgumentException("Non-Forge entrypoint preInit() must be static: " + className);
            }
        } catch (NoSuchMethodException optional) {
            // Only init() is required by the explicit static-entrypoint contract.
        }
        AgentLog.log("Initializing explicit non-Forge entrypoint: " + className);
        if (preInit != null) preInit.invoke(null);
        init.invoke(null);
        AgentLog.log("Initialized explicit non-Forge entrypoint: " + className);
    }

    private static void initMod(String className, String property,
                                ClassLoader modLoader, File configDir) throws Exception {
        if (!property.isEmpty() && !Boolean.getBoolean(property)) {
            AgentLog.log("Skipping " + className
                    + ", property guard not set: " + property);
            return;
        }

        String dotName = className.replace('/', '.');
        Class<?> modClass = Class.forName(dotName, true, modLoader);
        AgentLog.log("Loaded: " + dotName + " via " + modClass.getClassLoader());

        Object instance = modClass.getDeclaredConstructor().newInstance();
        AgentLog.log("Instantiated: " + dotName);

        ModLifecycleInvoker.invokeLifecycle(instance, configDir, modLoader);
    }

    static MappingRegistry loadMappings() {
        String resource = "/mappings/combined.csv";
        try (InputStream is = AgentBootstrap.class.getResourceAsStream(resource)) {
            if (is == null) {
                AgentLog.log("Mappings not found: " + resource
                        + " -- runtime remapping will not work!");
                return new MappingRegistry();
            }
            MappingRegistry mappings = MappingRegistry.load(is);
            AgentLog.log("Loaded combined mappings: "
                    + mappings.classCount() + " classes, "
                    + mappings.methodCount() + " methods, "
                    + mappings.fieldCount() + " fields.");
            return mappings;
        } catch (Exception e) {
            AgentLog.log("Failed to load mappings " + resource + ": " + e);
            return new MappingRegistry();
        }
    }

    static String buildModListProperty(List<ModEntry> mods) {
        StringBuilder sb = new StringBuilder();
        for (ModEntry mod : mods) {
            File f = new File(mod.getJar());
            if (!f.exists()) continue;
            if (mod.getEntrypoint() != null && !mod.getEntrypoint().isEmpty()) {
                if (sb.length() > 0) sb.append(",");
                sb.append(mod.getEntrypoint().replace('.', '/'))
                        .append("|").append(mod.getProperty() != null ? mod.getProperty() : "")
                        .append("|static");
                AgentLog.log("Configured non-Forge entrypoint: " + mod.getEntrypoint());
                continue;
            }
            try (JarFile jf = new JarFile(f)) {
                for (java.util.jar.JarEntry entry : Collections.list(jf.entries())) {
                    if (!entry.getName().endsWith(".class")) continue;
                    try (InputStream is = jf.getInputStream(entry)) {
                        byte[] bytes = toByteArray(is);
                        ClassReader cr = new ClassReader(bytes);
                        String[] modClass = {null};
                        cr.accept(new ClassVisitor(Opcodes.ASM9) {
                            private String className;
                            @Override public void visit(int v, int a, String name, String sig, String sup, String[] i) {
                                this.className = name;
                            }
                            @Override
                            public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                                if (FORGE_MOD_DESC.equals(desc)) modClass[0] = className;
                                return null;
                            }
                        }, ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES);

                        if (modClass[0] != null) {
                            if (sb.length() > 0) sb.append(",");
                            sb.append(modClass[0])
                                    .append("|").append(mod.getProperty() != null ? mod.getProperty() : "");
                            AgentLog.log("Discovered @Mod: " + modClass[0]);
                        }
                    }
                }
            } catch (IOException e) {
                AgentLog.log("Failed to scan JAR: " + mod.getJar() + " -- " + e);
            }
        }
        return sb.toString();
    }

    private static Set<String> collectModClassNames(List<ModEntry> mods) {
        Set<String> names = new HashSet<>();
        for (ModEntry mod : mods) {
            File f = new File(mod.getJar());
            if (!f.exists()) continue;

            List<String> excludes = parseExcludePrefixes(mod.getExclude());

            try (JarFile jf = new JarFile(f)) {
                jf.stream()
                        .filter(e -> e.getName().endsWith(".class"))
                        .map(e -> e.getName().replace(".class", ""))
                        .filter(name -> excludes.stream().noneMatch(name::startsWith))
                        .forEach(names::add);
            } catch (IOException e) {
                AgentLog.log("Failed to index JAR: " + mod.getJar() + " -- " + e);
            }
        }
        return names;
    }

    /// Exclusions.
    private static List<String> parseExcludePrefixes(String exclude) {
        if (exclude == null || exclude.isEmpty()) return Collections.emptyList();
        final List<String> result = new ArrayList<>();
        for (String s : exclude.split(",")) {
            s = s.trim();
            if (!s.isEmpty()) result.add(s);
        }
        return result;
    }

    static String extractJsonString(String json, String key) {
        String search = "\"" + key + "\"";
        int ki = json.indexOf(search);
        if (ki == -1) return null;
        int colon = json.indexOf(':', ki + search.length());
        if (colon == -1) return null;
        int start = json.indexOf('"', colon + 1);
        if (start == -1) return null;
        int end = json.indexOf('"', start + 1);
        if (end == -1) return null;
        return json.substring(start + 1, end);
    }

    static List<String> extractJsonStringArray(String json, String key) {
        List<String> result = new ArrayList<>();
        String search = "\"" + key + "\"";
        int ki = json.indexOf(search);
        if (ki == -1) return result;
        int colon = json.indexOf(':', ki + search.length());
        if (colon == -1) return result;
        int arrayStart = json.indexOf('[', colon + 1);
        if (arrayStart == -1) return result;
        int arrayEnd = json.indexOf(']', arrayStart + 1);
        if (arrayEnd == -1) return result;
        for (String token : json.substring(arrayStart + 1, arrayEnd).split(",")) {
            token = token.trim();
            if (token.startsWith("\"") && token.endsWith("\""))
                result.add(token.substring(1, token.length() - 1));
        }
        return result;
    }

    private static byte[] toByteArray(InputStream in) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int len;

        while ((len = in.read(buffer)) != -1) {
            out.write(buffer, 0, len);
        }

        return out.toByteArray();
    }

    private static void registerAsm(Instrumentation inst, ModEntry mod, MappingRegistry mappings,
                                    AtomicReference<Environment> runtimeEnvironment,
                                    Set<String> modClassNames, Path mixinPatchDir) {
        final String asmClassName = mod.getAsm();
        final File modJarFile = new File(mod.getJar());

        // Load and initialize the transformer immediately. Lunar can define a handful of
        // Minecraft classes while its mixin pipeline is still starting; waiting for the
        // first GenesisClassLoader callback allowed those classes to permanently bypass ASM.
        final AsmHandle eagerHandle = initializeAsmTransformer(
                asmClassName, modJarFile, ClassLoader.getSystemClassLoader(),
                runtimeEnvironment.get() == Environment.OBF ? "ave"
                        : runtimeEnvironment.get() == Environment.SRG ? "srg"
                        : "net/minecraft/client/Minecraft");

        ClassFileTransformer asmTransformer = new ClassFileTransformer() {
            private volatile Method transform = eagerHandle != null ? eagerHandle.transform : null;
            private volatile ClassLoader transformerLoader = eagerHandle != null ? eagerHandle.loader : null;
            private final Object initLock = new Object();
            private final Set<ClassLoader> preparedLoaders =
                    Collections.newSetFromMap(new IdentityHashMap<ClassLoader, Boolean>());

            @Override
            public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) {
                if (className == null) return null;
                // The diagnostic retransformation must not re-add accessor methods/interfaces.
                if (BadlionTickProbe.isProbeRetransformation(classBeingRedefined)) return null;

                final boolean minecraftEntry = className.equals("ave")
                        || className.equals("net/minecraft/client/Minecraft");
                // Badlion defines Entity, GuiScreen, Block and others before Minecraft; preparing only
                // on Minecraft let those base classes bypass the mod's patches for the whole session.
                final boolean badlionGameClass = BadlionFinalStage.isInstalled()
                        && runtimeEnvironment.get() == Environment.OBF
                        && mappings.hasClass(MappingRegistry.Namespace.OBF, className);
                final boolean genesisLoader = isGenesisLoader(loader);
                final boolean ichorLoader = isIchorLoader(loader);
                final boolean prepared;
                synchronized (preparedLoaders) {
                    prepared = preparedLoaders.contains(loader);
                }
                if (loader == null || (!minecraftEntry && !badlionGameClass && !genesisLoader && !ichorLoader && !prepared)) {
                    return null;
                }

                // The implementation lives in an isolated loader, but injected hook owners
                // must resolve in the same game loader as Minecraft itself.
                synchronized (preparedLoaders) {
                    if (!preparedLoaders.contains(loader)) {
                        try {
                            final ClassLoader hookLoader = ichorLoader
                                    && isGenesisLoader(loader.getParent())
                                    ? loader.getParent() : loader;

                            // This usually runs before the mixin registrar. The pre-patched mixin classes
                            // must still win over this jar's originals, so their dir goes first.
                            if (mixinPatchDir != null) {
                                addJarToClassLoader(hookLoader, mixinPatchDir.toFile());
                            }

                            addJarToClassLoader(hookLoader, modJarFile);
                            if (isGenesisLoader(hookLoader)) {
                                preloadGenesisClassCache(hookLoader, modJarFile, mixinPatchDir);
                            }

                            preparedLoaders.add(loader);
                            AgentLog.log("ASM prepared target loader " + loader
                                    + " with hook owner loader " + hookLoader);
                        } catch (Exception e) {
                            AgentLog.log("Failed to prepare ASM target loader " + loader + ": " + e);
                            return null;
                        }
                    }
                }

                Method m = transform;
                if (m == null) {
                    synchronized (initLock) {
                        m = transform;
                        if (m == null) {
                            final AsmHandle handle = initializeAsmTransformer(
                                    asmClassName, modJarFile, loader,
                                    className.equals("ave") ? "ave" : "net/minecraft/client/Minecraft");
                            if (handle == null) {
                                return null;
                            }
                            transformerLoader = handle.loader;
                            transform = handle.transform;
                            m = handle.transform;
                        }
                    }
                }

                try {
                    ClassLoader frameResources = BadlionFinalStage.isInstalled()
                            ? new BadlionFrameResources(loader) : loader;
                    byte[] transformed = (byte[]) m.invoke(null, className, classfileBuffer, frameResources);
                    if (transformed != null && transformed != classfileBuffer
                            && runtimeEnvironment.get() != Environment.MCP) {
                        if (BadlionFinalStage.isInstalled()) {
                            transformed = RuntimeRemapper.remapInjectedBytes(classfileBuffer, transformed,
                                    mappings, runtimeEnvironment.get(), modClassNames, loader);
                            AgentLog.log("Badlion post-ASM remap scoped to additions for " + className
                                    + "; original client member names retained");
                        } else {
                            transformed = RuntimeRemapper.remapBytes(transformed, mappings,
                                    runtimeEnvironment.get(), modClassNames, loader);
                        }
                    }
                    if (minecraftEntry) BadlionTickProbe.reportOutput(className, classfileBuffer, transformed);
                    return transformed;
                } catch (InvocationTargetException e) {
                    AgentLog.log("Failed transforming " + className);
                    AsmFailureDiagnostics.log(className, classfileBuffer, mappings, runtimeEnvironment.get());
                    final Throwable cause = e.getCause();
                    if (cause != null) {
                        final StringWriter sw = new StringWriter();
                        cause.printStackTrace(new PrintWriter(sw));
                        AgentLog.log(sw.toString());
                    }
                    return classfileBuffer;
                } catch (IllegalAccessException e) {
                    throw new RuntimeException(e);
                }
            }
        };
        if (BadlionFinalStage.enabledForLaunch()) {
            BadlionFinalStage.register(asmTransformer, collectModClassNames(Collections.singletonList(mod)));
        } else {
            inst.addTransformer(asmTransformer, true);
        }
        AgentLog.log("ASM registered for: " + asmClassName);
    }

    private static AsmHandle initializeAsmTransformer(String asmClassName, File modJarFile,
                                                       ClassLoader targetLoader,
                                                       String minecraftClassName) {
        try {
            if (!modJarFile.exists()) {
                AgentLog.log("ASM: jar not found, skipping: " + modJarFile);
                return null;
            }

            // Keep transformer implementation classes isolated from the system loader.
            final URLClassLoader asmLoader = new URLClassLoader(
                    new URL[]{modJarFile.toURI().toURL()},
                    ClassLoader.getSystemClassLoader()) {
                @Override
                protected synchronized Class<?> loadClass(String name, boolean resolve)
                        throws ClassNotFoundException {
                    // The game loader receives this same JAR later. Parent-first lookup
                    // would split lazily loaded transformer helpers from their initialized
                    // hook registry, silently leaving the game without any hooks.
                    Class<?> type = findLoadedClass(name);
                    if (type == null && !name.startsWith("java.")
                            && !name.startsWith("javax.") && !name.startsWith("sun.")) {
                        try {
                            type = findClass(name);
                        } catch (ClassNotFoundException ignored) {
                            // Platform and agent dependencies remain parent-owned.
                        }
                    }
                    if (type == null) type = super.loadClass(name, false);
                    if (resolve) resolveClass(type);
                    return type;
                }
            };
            final Class<?> transformerClass = Class.forName(asmClassName, true, asmLoader);

            try {
                final Method init = transformerClass.getMethod(
                        "init", File.class, ClassLoader.class, String.class);
                init.invoke(null, modJarFile, targetLoader, minecraftClassName);
                AgentLog.log("ASM init(File, ClassLoader, String) called for: " + asmClassName);
            } catch (NoSuchMethodException extendedMissing) {
                try {
                    final Method init = transformerClass.getMethod(
                            "init", File.class, ClassLoader.class);
                    init.invoke(null, modJarFile, targetLoader);
                    AgentLog.log("ASM init(File, ClassLoader) called for: " + asmClassName);
                } catch (NoSuchMethodException legacyMissing) {
                    AgentLog.log("ASM transformer has no supported init method, skipping: " + asmClassName);
                }
            }

            final Method transform = transformerClass.getMethod(
                    "transform", String.class, byte[].class, ClassLoader.class);
            return new AsmHandle(asmLoader, transform);
        } catch (Exception e) {
            AgentLog.log("Failed to initialize ASM transformer: " + asmClassName + " - " + e);
            e.printStackTrace();
            return null;
        }
    }

    private static final class AsmHandle {
        final ClassLoader loader;
        final Method transform;

        AsmHandle(ClassLoader loader, Method transform) {
            this.loader = loader;
            this.transform = transform;
        }
    }

    private static boolean isGenesisLoader(ClassLoader loader) {
        if (loader == null) return false;
        return String.valueOf(loader).startsWith("GenesisClassLoader(");
    }

    private static boolean isIchorLoader(ClassLoader loader) {
        if (loader == null) return false;
        return String.valueOf(loader).startsWith("IchorClassLoader(");
    }

    @SuppressWarnings("unchecked")
    private static ConcurrentMap<String, byte[]> genesisClassCache(ClassLoader loader) throws Exception {
        for (Method method : loader.getClass().getMethods()) {
            if (method.getParameterCount() == 0 && ConcurrentMap.class.isAssignableFrom(method.getReturnType())) {
                return (ConcurrentMap<String, byte[]>) method.invoke(loader);
            }
        }
        throw new IllegalStateException("Genesis class byte cache accessor was not found");
    }

    /// Only entries that are already cached are replaced, anything else keeps loading from the patch dir.
    private static void replaceGenesisClassCache(ClassLoader loader, Map<String, byte[]> patched) {
        try {
            final ConcurrentMap<String, byte[]> classCache = genesisClassCache(loader);

            int replaced = 0;
            for (Map.Entry<String, byte[]> entry : patched.entrySet()) {
                if (classCache.replace(entry.getKey(), entry.getValue()) != null) replaced++;
            }

            if (replaced > 0) AgentLog.log("Replaced " + replaced + " cached mixin classes with pre-patched bytes.");
        } catch (Exception e) {
            AgentLog.log("Failed to update Genesis byte cache with pre-patched mixins: " + e);
        }
    }

    private static void preloadGenesisClassCache(ClassLoader loader, File jarFile, Path mixinPatchDir) throws Exception {
        final ConcurrentMap<String, byte[]> classCache = genesisClassCache(loader);
        int added = 0;

        try (JarFile jar = new JarFile(jarFile)) {
            final Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                final JarEntry entry = entries.nextElement();
                final String entryName = entry.getName();
                if (entry.isDirectory() || !entryName.endsWith(".class")
                        || entryName.startsWith("META-INF/")) {
                    continue;
                }

                final String className = entryName.substring(0, entryName.length() - 6)
                        .replace('/', '.');
                // Prefer the mixin registrars patched copy.
                final Path patched = mixinPatchDir != null ? mixinPatchDir.resolve(entryName) : null;
                try (InputStream input = patched != null && Files.isRegularFile(patched)
                        ? Files.newInputStream(patched) : jar.getInputStream(entry)) {
                    if (classCache.putIfAbsent(className, toByteArray(input)) == null) {
                        added++;
                    }
                }
            }
        }

        AgentLog.log("Preloaded " + added + " ASM JAR classes into Genesis byte cache.");
    }

    private static void addJarToClassLoader(ClassLoader loader, File jarFile) throws Exception {
        final URL url = jarFile.toURI().toURL();
        try {
            final Method addURL = loader.getClass().getMethod("addURL", URL.class);
            addURL.invoke(loader, url);
            AgentLog.log("ASM JAR added to target classloader: " + url);
            return;
        } catch (NoSuchMethodException ignored) {
            // Fall through for ordinary URLClassLoader implementations, not fatal.
        }

        if (loader instanceof URLClassLoader) {
            final Method addURL = URLClassLoader.class.getDeclaredMethod("addURL", URL.class);
            addURL.setAccessible(true);
            addURL.invoke(loader, url);
            AgentLog.log("ASM JAR added to URLClassLoader: " + url);
            return;
        }

        throw new IllegalStateException("Target classloader cannot accept URLs: " + loader.getClass().getName());
    }
}
