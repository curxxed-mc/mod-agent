package net.curxxed.dev.agent;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.concurrent.atomic.AtomicLong;
import org.objectweb.asm.*;

/** A bounded, event-free probe of one existing method body; no class-shape changes. */
public final class BadlionTickProbe {
    private static final String OWNER = "net/curxxed/dev/agent/BadlionTickProbe";
    private static final AtomicLong TICKS = new AtomicLong();
    private static volatile Class<?> retransformationTarget;
    private static volatile boolean observing;

    public static boolean isProbeRetransformation(Class<?> type) {
        return type != null && type == retransformationTarget;
    }

    public static void onTick() {
        if (observing && TICKS.incrementAndGet() == 1) {
            AgentLog.log("Badlion late tick probe executed on " + Thread.currentThread().getName());
        }
    }

    public static void reportOutput(String className, byte[] before, byte[] after) {
        if (!BadlionBootstrapFallback.isBadlionLaunch(System.getProperty("sun.java.command", ""))) return;
        AgentLog.log("Badlion ASM output for " + className + ": changed="
                + (after != null && !java.util.Arrays.equals(before, after))
                + " input=" + summary(before) + " output=" + summary(after));
    }

    static String summary(byte[] bytes) {
        if (bytes == null) return "null";
        int[] hooks = {0};
        ClassReader reader = new ClassReader(bytes);
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public MethodVisitor visitMethod(int access, String name, String desc,
                                                       String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override public void visitMethodInsn(int opcode, String owner, String name,
                                                          String descriptor, boolean iface) {
                        if (BadlionFinalStage.isModOwner(owner)) hooks[0]++;
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return "{interfaces=" + java.util.Arrays.toString(reader.getInterfaces())
                + ",modCalls=" + hooks[0] + ",bytes=" + bytes.length + "}";
    }

    static byte[] patch(byte[] bytes, String method) {
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        boolean[] found = {false}, present = {false};
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                       String signature, String[] exceptions) {
                if (!name.equals(method) || !descriptor.equals("()V")) return null;
                found[0] = (access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override public void visitMethodInsn(int opcode, String owner, String name,
                                                          String desc, boolean iface) {
                        if (owner.equals(OWNER) && name.equals("onTick")) present[0] = true;
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        if (!found[0] || present[0]) return null;
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                       String signature, String[] exceptions) {
                MethodVisitor visitor = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!name.equals(method) || !descriptor.equals("()V")) return visitor;
                return new MethodVisitor(Opcodes.ASM9, visitor) {
                    @Override public void visitCode() {
                        super.visitCode();
                        super.visitMethodInsn(Opcodes.INVOKESTATIC, OWNER, "onTick", "()V", false);
                    }
                };
            }
        }, 0);
        return writer.toByteArray();
    }

    public static void start(Instrumentation instrumentation, Class<?> minecraft, String tickMethod) {
        Thread probe = new Thread(() -> {
            if (!instrumentation.isRetransformClassesSupported()
                    || !instrumentation.isModifiableClass(minecraft)) {
                AgentLog.log("Badlion late tick probe unavailable: Minecraft cannot be retransformed.");
                return;
            }
            ClassFileTransformer transformer = new ClassFileTransformer() {
                @Override public byte[] transform(ClassLoader loader, String name, Class<?> type,
                                                  ProtectionDomain domain, byte[] bytes) {
                    if (type != minecraft) return null;
                    try {
                        AgentLog.log("Badlion late tick probe input: " + summary(bytes));
                        byte[] result = patch(bytes, tickMethod);
                        AgentLog.log("Badlion late tick probe body injected=" + (result != null));
                        return result;
                    } catch (Throwable failure) {
                        AgentLog.log("Badlion late tick probe transform failed: " + failure);
                        return null;
                    }
                }
            };
            instrumentation.addTransformer(transformer, true);
            try {
                TICKS.set(0);
                observing = true;
                retransformationTarget = minecraft;
                instrumentation.retransformClasses(minecraft);
                AgentLog.log("Badlion late tick retransformation accepted; observing for 10 seconds.");
            } catch (Throwable failure) {
                AgentLog.log("Badlion late tick retransformation rejected: " + failure);
                observing = false;
                return;
            } finally {
                retransformationTarget = null;
                instrumentation.removeTransformer(transformer);
            }
            try {
                Thread.sleep(10_000);
                AgentLog.log("Badlion late tick probe result: observedTicks=" + TICKS.get());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                observing = false;
            }
        }, "Mod-Agent-Badlion-Tick-Probe");
        probe.setDaemon(true);
        probe.start();
    }
}
