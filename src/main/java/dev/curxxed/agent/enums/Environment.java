package dev.curxxed.agent.enums;

import dev.curxxed.agent.AgentLog;
import dev.curxxed.agent.mappings.MappingRegistry;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import java.io.InputStream;

public enum Environment {

    OBF(MappingRegistry.Namespace.OBF),
    SRG(MappingRegistry.Namespace.SRG),
    MCP(MappingRegistry.Namespace.MCP);

    private final MappingRegistry.Namespace namespace;

    Environment(MappingRegistry.Namespace namespace) {
        this.namespace = namespace;
    }

    public MappingRegistry.Namespace namespace() {
        return namespace;
    }

    public static Environment detectRuntimeEnvironment() {
        Environment environment = detectFromResources();
        if (environment == null) environment = MCP;
        AgentLog.log("Detected initial mapping environment: " + environment);
        return environment;
    }

    public static Environment detectFromMinecraftClass(String className, byte[] classfileBuffer) {
        if ("ave".equals(className)) return OBF;
        if (!"dev/minecraft/client/Minecraft".equals(className)) return detectRuntimeEnvironment();

        final boolean[] hasSrgStartGame = {false};
        final boolean[] hasMcpStartGame = {false};
        try {
            final ClassReader cr = new ClassReader(classfileBuffer);
            cr.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions) {
                    if ("()V".equals(descriptor)) {
                        if ("func_71384_a".equals(name)) hasSrgStartGame[0] = true;
                        if ("startGame".equals(name)) hasMcpStartGame[0] = true;
                    }
                    return null;
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES);
        } catch (RuntimeException ignored) {
            return MCP;
        }

        if (hasSrgStartGame[0]) return SRG;
        return MCP;
    }

    private static Environment detectFromResources() {
        final ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
        if (hasResource(contextLoader, "ave.class")) return OBF;
        if (hasResource(contextLoader, "dev/minecraft/client/Minecraft.class")) {
            boolean isLunar = hasResource(contextLoader, "com/moonsworth/lunar/genesis/Genesis.class");
            if (hasResource(contextLoader, "dev/minecraftforge/common/MinecraftForge.class") && !isLunar) {
                return SRG;
            }
            return MCP;
        }
        return null;
    }

    private static boolean hasResource(ClassLoader loader, String name) {
        try {
            final InputStream stream = loader != null
                    ? loader.getResourceAsStream(name)
                    : ClassLoader.getSystemResourceAsStream(name);
            if (stream == null) return false;
            stream.close();
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }
}
