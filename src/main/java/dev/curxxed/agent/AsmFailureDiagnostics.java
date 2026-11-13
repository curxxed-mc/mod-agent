package dev.curxxed.agent;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import dev.curxxed.agent.enums.Environment;
import dev.curxxed.agent.mappings.MappingRegistry;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

final class AsmFailureDiagnostics {
    static List<String> describe(String className, byte[] bytes, MappingRegistry mappings, Environment environment) {
        List<String> report = new ArrayList<>();
        String minecraft = "net/minecraft/client/Minecraft";
        String renderer = "net/minecraft/client/renderer/EntityRenderer";
        Set<String> methods = new LinkedHashSet<>();
        if (className.equals(mappings.mapClass(minecraft, environment.namespace()))) {
            methods.add(mappings.mapMethodName(minecraft, "runTick", "()V", environment.namespace()) + "()V");
            methods.add(mappings.mapMethodName(minecraft, "dispatchKeypresses", "()V", environment.namespace()) + "()V");
            methods.add("runTick()V");
            methods.add("dispatchKeypresses()V");
            methods.add("runInputTick()V");
        } else if (className.equals(mappings.mapClass(renderer, environment.namespace()))) {
            methods.add(mappings.mapMethodName(renderer, "hurtCameraEffect", "(F)V", environment.namespace()) + "(F)V");
            methods.add("hurtCameraEffect(F)V");
        } else return report;
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions) {
                if (!methods.contains(name + descriptor)) return null;
                Set<String> calls = new LinkedHashSet<>();
                Set<String> clientCalls = new LinkedHashSet<>();
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String member,
                            String calledDescriptor, boolean isInterface) {
                        if (calls.size() < 40) calls.add("L" + owner + ";" + member + calledDescriptor);
                        if (clientCalls.size() < 40 && (owner.equals(className) || owner.startsWith("net/badlion/"))) {
                            clientCalls.add("L" + owner + ";" + member + calledDescriptor);
                        }
                    }

                    @Override
                    public void visitEnd() {
                        report.add("ASM target call sites " + className + "#" + name + descriptor + ": " + calls
                                + "; local/client calls: " + clientCalls);
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        if (report.isEmpty()) report.add("ASM failed-target methods not found in " + className + ": " + methods);
        return report;
    }

    static void log(String className, byte[] bytes, MappingRegistry mappings, Environment environment) {
        try {
            for (String line : describe(className, bytes, mappings, environment)) AgentLog.log(line);
        } catch (RuntimeException failure) {
            AgentLog.log("ASM failure call-site inspection unavailable for " + className + ": " + failure.getClass().getSimpleName());
        }
    }
}
