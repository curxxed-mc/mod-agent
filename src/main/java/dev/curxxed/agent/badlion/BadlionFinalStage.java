package dev.curxxed.agent.badlion;

import dev.curxxed.agent.AgentLog;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.objectweb.asm.*;

/**
 * Last-registered Java transformer for Badlion launches. JVMTI runs class-file hooks in
 * agent load order, so it only sees Badlion's final class bytes when this agent's
 * -javaagent option comes after Badlion's native agent on the command line.
 */
public final class BadlionFinalStage {
    private static final List<Registration> TRANSFORMERS = new CopyOnWriteArrayList<>();
    private static final class Registration {
        final ClassFileTransformer transformer;
        final java.util.Set<String> owners;
        Registration(ClassFileTransformer transformer, java.util.Set<String> owners) {
            this.transformer = transformer;
            this.owners = java.util.Collections.unmodifiableSet(new java.util.HashSet<>(owners));
        }
    }
    private static volatile boolean installed;
    private static volatile Instrumentation instrumentation;
    private static final java.util.Map<ClassLoader, java.util.Map<String, byte[]>> METADATA =
            java.util.Collections.synchronizedMap(new java.util.IdentityHashMap<>());

    static void register(ClassFileTransformer transformer) {
        register(transformer, java.util.Collections.emptySet());
    }
    public static void register(ClassFileTransformer transformer, java.util.Set<String> owners) {
        TRANSFORMERS.add(new Registration(transformer, owners));
    }
    public static boolean isInstalled() { return installed; }
    public static void setInstrumentation(Instrumentation value) { instrumentation = value; }

    public static boolean enabledForLaunch() {
        if (!BadlionBootstrapFallback.isBadlionLaunch(System.getProperty("sun.java.command", ""))) return false;
        String option = System.getProperty("modagent.badlion.finalStage", "on");
        return !option.equalsIgnoreCase("off") && !option.equalsIgnoreCase("false");
    }

    private static java.util.Map<String, byte[]> cache(ClassLoader loader) {
        synchronized (METADATA) {
            return METADATA.computeIfAbsent(loader, ignored -> new java.util.concurrent.ConcurrentHashMap<>());
        }
    }

    private static byte[] header(String name, int access, String parent, String[] interfaces) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, access, name, null, parent, interfaces);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] remember(ClassLoader loader, String name, ClassReader reader) {
        byte[] value = header(name, reader.getAccess(), reader.getSuperName(), reader.getInterfaces());
        cache(loader).put(name, value);
        return value;
    }

    /** Frame metadata only: no direct decoding of Badlion resources. */
    public static byte[] runtimeMetadata(String name, ClassLoader loader, boolean resolveProtected) {
        byte[] known = cache(loader).get(name);
        if (known != null) return known;
        Class<?> type = null;
        if (instrumentation != null) {
            for (Class<?> candidate : instrumentation.getAllLoadedClasses()) {
                if (candidate.getClassLoader() == loader && candidate.getName().equals(name.replace('/', '.'))) {
                    type = candidate;
                    break;
                }
            }
        }
        try {
            if (type == null && resolveProtected && name.startsWith("net/badlion/")) {
                // Use Badlion's normal class loader, never initialize the class here.
                type = Class.forName(name.replace('/', '.'), false, loader);
            }
            if (type == null) return null;
            Class<?> parent = type.getSuperclass();
            Class<?>[] interfaces = type.getInterfaces();
            String[] names = new String[interfaces.length];
            for (int i = 0; i < names.length; i++) names[i] = interfaces[i].getName().replace('.', '/');
            byte[] value = header(name, type.getModifiers(),
                    parent == null ? null : parent.getName().replace('.', '/'), names);
            cache(loader).put(name, value);
            AgentLog.log("Badlion frame metadata resolved from runtime type: " + name);
            return value;
        } catch (Throwable failure) {
            AgentLog.log("Badlion frame metadata unresolved " + name + ": " + failure);
            return null;
        }
    }

    public static synchronized void install() {
        if (installed) return;
        if (!enabledForLaunch()) {
            if (BadlionBootstrapFallback.isBadlionLaunch(System.getProperty("sun.java.command", ""))) {
                AgentLog.log("Badlion final stage explicitly disabled; working hook injection is not guaranteed.");
            }
            return;
        }
        if (instrumentation == null) {
            AgentLog.log("Badlion final stage unavailable: no Instrumentation.");
            return;
        }
        logAgentOrder(ManagementFactory.getRuntimeMXBean().getInputArguments());
        instrumentation.addTransformer(new ClassFileTransformer() {
            @Override public byte[] transform(ClassLoader loader, String name, Class<?> redefining,
                                              ProtectionDomain domain, byte[] bytes) {
                // Retransformations start again from the pre-hook bytes, so mod hooks must be
                // reapplied every time or the class loses them.
                if (name == null) return null;
                return BadlionFinalStage.transform(loader, name, bytes);
            }
        }, true);
        installed = true;
        System.setProperty("modagent.badlion.backend", "ready");
        AgentLog.log("Badlion final stage enabled (pure Java).");
        retransformEarlyClasses();
    }

    /**
     * Badlion's native agent defines certain game classes during JVM startup, before our own java agent runs,
     * so they never end up reaching the class-file hook. Retransforming them gives the same bytes to mod
     * transformers that Badlion would have in its final stage.
     */
    // TODO: Check limitations
    private static void retransformEarlyClasses() {
        if (TRANSFORMERS.isEmpty() || !instrumentation.isRetransformClassesSupported()) return;

        final List<Class<?>> early = new ArrayList<>();
        for (Class<?> type : instrumentation.getAllLoadedClasses()) {
            // Obfuscated Minecraft classes live in the default package.
            if (type.getClassLoader() == null || type.isArray() || type.getName().indexOf('.') >= 0) continue;
            if (instrumentation.isModifiableClass(type)) early.add(type);
        }

        if (early.isEmpty()) return;
        final List<String> names = new ArrayList<>();
        for (Class<?> type : early) {
            names.add(type.getName());
        }

        AgentLog.log("Badlion loaded " + early.size() + " game classes before this agent: " + names);

        for (Class<?> type : early) {
            try {
                instrumentation.retransformClasses(type);
            } catch (Throwable t) {
                AgentLog.log("Badlion early class " + type.getName() + " could not be patched: " + t);
            }
        }
    }

    private static void logAgentOrder(List<String> arguments) {
        List<String> agents = agentOrder(arguments);
        AgentLog.log("JVM agent load order: " + agents);
        if (javaAgentPrecedesNative(agents)) {
            AgentLog.log("WARNING: a native agent loads after this Java agent. Its class replacements run later "
                    + "and can discard mod hooks. Place -javaagent after it (as the last JVM option).");
        }
    }

    /** Agent options with paths reduced to file names; other JVM options are never logged. */
    static List<String> agentOrder(List<String> arguments) {
        List<String> agents = new ArrayList<>();
        for (String argument : arguments) {
            String kind = argument.startsWith("-javaagent:") ? "javaagent"
                    : argument.startsWith("-agentpath:") ? "agentpath"
                    : argument.startsWith("-agentlib:") ? "agentlib" : null;
            if (kind == null) continue;
            String target = argument.substring(kind.length() + 2);
            int options = target.indexOf('=');
            if (options >= 0) target = target.substring(0, options);
            if (kind.equals("agentlib") && target.equals("jdwp")) continue;
            agents.add(kind + ":" + new java.io.File(target).getName());
        }
        return agents;
    }

    static boolean javaAgentPrecedesNative(List<String> agents) {
        int firstJava = -1, lastNative = -1;
        for (int i = 0; i < agents.size(); i++) {
            if (agents.get(i).startsWith("javaagent:")) { if (firstJava < 0) firstJava = i; }
            else lastNative = i;
        }
        return firstJava >= 0 && lastNative > firstJava;
    }

    /** Called for initial class definitions and for every retransformation. */
    public static byte[] transform(ClassLoader loader, String name, byte[] bytes) {
        if (loader == null || bytes == null) return null;
        byte[] result = bytes;
        try {
            ClassReader reader = new ClassReader(bytes);
            remember(loader, name, reader);
            if (name.startsWith("net/badlion/")) return null;
            for (Registration registration : TRANSFORMERS) {
                // Per-mod guard: one mod's existing hook must not suppress another mod.
                if (containsOwnedHooks(result, registration.owners)) continue;
                byte[] changed = registration.transformer.transform(loader, name, null, null, result);
                if (changed != null) result = changed;
            }
            if (java.util.Arrays.equals(result, bytes)) return null;
            remember(loader, name, new ClassReader(result));
            AgentLog.log("Badlion FINAL output " + name + " input="
                    + BadlionTickProbe.summary(bytes) + " output=" + BadlionTickProbe.summary(result));
            return result;
        } catch (Throwable failure) {
            StringBuilder prefix = new StringBuilder();
            for (int i = 0; i < Math.min(12, bytes.length); i++) prefix.append(String.format("%02x", bytes[i] & 255));
            AgentLog.log("Badlion final stage failed for " + name + " loader=" + loader
                    + " bytes=" + bytes.length + " header=" + prefix + ": " + failure);
            java.io.StringWriter trace = new java.io.StringWriter();
            failure.printStackTrace(new java.io.PrintWriter(trace));
            AgentLog.log(trace.toString());
            return null;
        }
    }

    static boolean containsOwnedHooks(byte[] bytes, java.util.Set<String> owners) {
        if (owners.isEmpty()) return false;
        boolean[] found = {false};
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public void visit(int version, int access, String name, String signature,
                                         String parent, String[] interfaces) {
                for (String type : interfaces) {
                    if (owners.contains(type)) found[0] = true;
                }
            }
            @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                        String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override public void visitMethodInsn(int opcode, String owner, String name,
                                                          String descriptor, boolean iface) {
                        if (owners.contains(owner)) found[0] = true;
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return found[0];
    }

    static boolean isModOwner(String name) {
        for (Registration registration : TRANSFORMERS) if (registration.owners.contains(name)) return true;
        return false;
    }
}
