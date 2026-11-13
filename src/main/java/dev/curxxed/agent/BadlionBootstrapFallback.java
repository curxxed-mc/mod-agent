package dev.curxxed.agent;

import dev.curxxed.agent.enums.Environment;
import dev.curxxed.agent.mappings.MappingRegistry;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;

/** Client-thread startup fallback for Badlion's native-modified startup path. */
public final class BadlionBootstrapFallback {

    private static final String MINECRAFT = "net/minecraft/client/Minecraft";
    private static final String BADLION_LAUNCH = "net.badlion.clientcommon.main.Launch";
    private static volatile Boolean badlionOnClasspath;

    /// Badlion starts net.minecraft.client.main.Main from BLClient.jar, which then loads Launch.
    public static boolean isBadlionLaunch(String command) {
        if (command.equals(BADLION_LAUNCH) || command.startsWith(BADLION_LAUNCH + " ")) return true;
        Boolean present = badlionOnClasspath;
        if (present == null) {
            present = ClassLoader.getSystemResource(BADLION_LAUNCH.replace('.', '/') + ".class") != null;
            badlionOnClasspath = present;
        }
        return present;
    }

    public static void start(Instrumentation instrumentation, MappingRegistry mappings,
                             AtomicReference<Environment> environment) {
        if (!isBadlionLaunch(System.getProperty("sun.java.command", ""))) return;
        AgentLog.log("Badlion startup fallback armed; waiting for initialized Minecraft resources.");
        Thread observer = new Thread(() -> {
            Class<?> minecraft = null;
            long deadline = System.nanoTime() + 120_000_000_000L;
            try {
                while (System.nanoTime() < deadline) {
                    if (Boolean.getBoolean("lunar.agent.bootstrap.initialized")) return;
                    if (minecraft == null) {
                        for (Class<?> candidate : instrumentation.getAllLoadedClasses()) {
                            if (candidate.getName().equals("ave")
                                    || candidate.getName().equals("net.minecraft.client.Minecraft")) {
                                minecraft = candidate;
                                AgentLog.log("Badlion fallback found Minecraft in " + minecraft.getClassLoader());
                                break;
                            }
                        }
                    }
                    if (minecraft != null) {
                        MappingRegistry.Namespace namespace = environment.get().namespace();
                        String getter = mappings.mapMethodName(MINECRAFT, "getMinecraft",
                                "()Lnet/minecraft/client/Minecraft;", namespace);
                        String font = mappings.mapFieldName(MINECRAFT, "fontRendererObj", null, namespace);
                        String textures = mappings.mapFieldName(MINECRAFT, "renderEngine", null, namespace);
                        Object instance = readyInstance(minecraft, getter, font, textures);
                        if (instance != null) {
                            String scheduler = mappings.mapMethodName(MINECRAFT, "addScheduledTask",
                                    "(Ljava/lang/Runnable;)Lcom/google/common/util/concurrent/ListenableFuture;", namespace);
                            ClassLoader loader = minecraft.getClassLoader();
                            Class<?> runtimeMinecraft = minecraft;
                            String tickMethod = mappings.mapMethodName(MINECRAFT, "runTick", "()V", namespace);
                            AgentLog.log("Badlion resources ready; queueing bootstrap via " + scheduler
                                    + " on " + instance.getClass().getName());
                            schedule(instance, minecraft, scheduler, () -> {
                                AgentLog.log("Badlion fallback executing on client thread.");
                                AgentBootstrap.bootstrapLoadedMods(loader);
                                inspectRuntime(instance, Thread.currentThread());
                                if (!BadlionFinalStage.isInstalled()) {
                                    BadlionTickProbe.start(instrumentation, runtimeMinecraft, tickMethod);
                                }
                            });
                            return;
                        }
                    }
                    Thread.sleep(250);
                }
                AgentLog.log("Badlion startup fallback timed out before Minecraft resources became ready.");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (Throwable failure) {
                AgentLog.log("Badlion startup fallback failed: " + failure);
            }
        }, "Mod-Agent-Badlion-Startup");
        observer.setDaemon(true);
        observer.start();
    }

    static Object readyInstance(Class<?> minecraft, String getter, String font, String textures)
            throws ReflectiveOperationException {
        Method method = minecraft.getDeclaredMethod(getter);
        method.setAccessible(true);
        Object instance = method.invoke(null);
        if (instance == null) return null;
        java.lang.reflect.Field fontField = minecraft.getDeclaredField(font);
        java.lang.reflect.Field textureField = minecraft.getDeclaredField(textures);
        fontField.setAccessible(true);
        textureField.setAccessible(true);
        return fontField.get(instance) != null && textureField.get(instance) != null ? instance : null;
    }

    static void schedule(Object instance, Class<?> minecraft, String name, Runnable task)
            throws ReflectiveOperationException {
        Method method = minecraft.getDeclaredMethod(name, Runnable.class);
        method.setAccessible(true);
        method.invoke(instance, task);
    }

    private static void inspectRuntime(Object minecraft, Thread clientThread) {
        Class<?> type = minecraft.getClass();
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            AgentLog.log("Badlion runtime class " + current.getName() + " interfaces="
                    + java.util.Arrays.toString(current.getInterfaces()));
        }
        AgentLog.log("Badlion bootstrap client stack:");
        for (StackTraceElement frame : clientThread.getStackTrace()) {
            AgentLog.log("  " + frame);
        }
        Thread sampler = new Thread(() -> {
            java.util.Set<String> frames = new java.util.TreeSet<>();
            try {
                for (int sample = 0; sample < 100 && clientThread.isAlive(); sample++) {
                    for (StackTraceElement frame : clientThread.getStackTrace()) {
                        String name = frame.getClassName();
                        if (name.equals("ave") || name.startsWith("net.badlion.")
                                || name.startsWith("net.minecraft.")) {
                            frames.add(name + "." + frame.getMethodName());
                        }
                    }
                    Thread.sleep(100);
                }
                AgentLog.log("Badlion observed client loop frames: " + frames);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }, "Mod-Agent-Badlion-Hook-Diagnostics");
        sampler.setDaemon(true);
        sampler.start();
    }
}
