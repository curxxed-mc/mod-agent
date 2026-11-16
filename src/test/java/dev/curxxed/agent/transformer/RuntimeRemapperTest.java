package dev.curxxed.agent.transformer;

import dev.curxxed.agent.enums.Environment;
import dev.curxxed.agent.mappings.MappingRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Remaps a fake mod class with the real mappings. The runtime's own class files come from a vanilla 1.8.9 jar,
 * set with -Dmodagent.test.vanillaJar or found in the launcher's versions/1.8.9 folder (%APPDATA%\.minecraft on
 * Windows, ~/Library/Application Support/minecraft on macOS, ~/.minecraft elsewhere). Skipped without one.
 */
class RuntimeRemapperTest {

    private static final String MOD = "test/ModGui";
    private static final String GUI_SCREEN = "net/minecraft/client/gui/GuiScreen";
    private static final String PLAYER = "net/minecraft/client/entity/EntityPlayerSP";
    private static final String DRAW_CENTERED = "(Lnet/minecraft/client/gui/FontRenderer;Ljava/lang/String;III)V";

    private static MappingRegistry mappings;
    private static File vanillaJar;

    @TempDir
    Path modDir;

    @BeforeAll
    static void load() throws Exception {
        final String configured = System.getProperty("modagent.test.vanillaJar");
        vanillaJar = configured != null ? new File(configured)
                : minecraftDirectory().resolve("versions").resolve("1.8.9").resolve("1.8.9.jar").toFile();
        try (InputStream in = RuntimeRemapperTest.class.getResourceAsStream("/mappings/combined.csv")) {
            mappings = MappingRegistry.load(in);
        }
    }

    /// MCP named mod on vanilla/Badlion: inherited fields and methods need the class hierarchy.
    @Test
    void mcpModOnObfuscatedRuntime() throws Exception {
        assumeTrue(vanillaJar.isFile(), "No vanilla 1.8.9 jar at " + vanillaJar);
        final List<String[]> refs = new ArrayList<>();
        field(refs, GUI_SCREEN, "width", "I", "axu.l");
        field(refs, MOD, "width", "I", MOD + ".l"); // Inherited from GuiScreen
        field(refs, PLAYER, "posX", "D", "bew.s"); // Declared in Entity
        method(refs, PLAYER, "isSneaking", "()Z", "bew.av");
        method(refs, PLAYER, "getDistanceToEntity", "(Lnet/minecraft/entity/Entity;)F", "bew.g"); // Only in Entity
        method(refs, MOD, "drawCenteredString", DRAW_CENTERED, MOD + ".a"); // Declared in Gui
        field(refs, PLAYER, "field_70165_t", "D", "bew.s"); // Reobfuscated names keep working
        method(refs, PLAYER, "func_70093_af", "()Z", "bew.av");
        method(refs, "org/lwjgl/Sys", "getVersion", "()Ljava/lang/String;", "org/lwjgl/Sys.getVersion"); // Not Minecraft
        method(refs, MOD, "hashCode", "()I", MOD + ".hashCode");

        final byte[] mod = modClass(refs);
        final ClassLoader runtime = new URLClassLoader(new URL[]{modDir.toUri().toURL(), vanillaJar.toURI().toURL()}, null);
        check(refs, RuntimeRemapper.remapBytes(mod, mappings, Environment.OBF, Collections.singleton(MOD), runtime));
    }

    /// Lunar: MCP runtime whose loader answers MCP class names with the obfuscated class file (e.g. avn).
    @Test
    void lunarStyleRuntime() throws Exception {
        assumeTrue(vanillaJar.isFile(), "No vanilla 1.8.9 jar at " + vanillaJar);
        final List<String[]> refs = new ArrayList<>();
        field(refs, PLAYER, "posX", "D", PLAYER + ".posX");
        method(refs, MOD, "drawCenteredString", DRAW_CENTERED, MOD + ".drawCenteredString");
        field(refs, PLAYER, "field_70165_t", "D", PLAYER + ".posX");
        method(refs, MOD, "func_73732_a", DRAW_CENTERED, MOD + ".drawCenteredString");
        field(refs, MOD, "field_146294_l", "I", MOD + ".width");

        final byte[] mod = modClass(refs);
        final URLClassLoader vanilla = new URLClassLoader(new URL[]{vanillaJar.toURI().toURL()}, null);
        final ClassLoader runtime = new ClassLoader(null) {
            @Override public InputStream getResourceAsStream(String name) {
                if (name.startsWith("test/")) {
                    try { return Files.newInputStream(modDir.resolve(name)); } catch (IOException e) { return null; }
                }
                final String className = name.substring(0, name.length() - ".class".length());
                return vanilla.getResourceAsStream(mappings.mapClass(className, MappingRegistry.Namespace.OBF) + ".class");
            }
        };
        check(refs, RuntimeRemapper.remapBytes(mod, mappings, Environment.MCP, Collections.singleton(MOD), runtime));
    }

    /// Where the official launcher keeps its files on this OS.
    private static Path minecraftDirectory() {
        final String home = System.getProperty("user.home");
        final String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.startsWith("windows")) {
            final String appData = System.getenv("APPDATA");
            return appData != null ? Paths.get(appData, ".minecraft") : Paths.get(home, "AppData", "Roaming", ".minecraft");
        }
        if (os.startsWith("mac")) return Paths.get(home, "Library", "Application Support", "minecraft");
        return Paths.get(home, ".minecraft");
    }

    private static void field(List<String[]> refs, String owner, String name, String desc, String expected) {
        refs.add(new String[]{"F", owner, name, desc, expected});
    }

    private static void method(List<String[]> refs, String owner, String name, String desc, String expected) {
        refs.add(new String[]{"M", owner, name, desc, expected});
    }

    /// A class extending GuiScreen with one method that uses every reference, also written to modDir so it can be read back.
    private byte[] modClass(List<String[]> refs) throws IOException {
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, MOD, null, GUI_SCREEN, null);
        final MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, "run", "()V", null, null);
        method.visitCode();
        for (String[] ref : refs) {
            if (ref[0].equals("F")) method.visitFieldInsn(Opcodes.GETSTATIC, ref[1], ref[2], ref[3]);
            else method.visitMethodInsn(ref[1].startsWith("org/lwjgl/") ? Opcodes.INVOKESTATIC : Opcodes.INVOKEVIRTUAL, ref[1], ref[2], ref[3], false);
        }
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        final byte[] bytes = writer.toByteArray();
        Files.createDirectories(modDir.resolve("test"));
        Files.write(modDir.resolve(MOD + ".class"), bytes);
        return bytes;
    }

    private static void check(List<String[]> refs, byte[] remapped) {
        final List<String> actual = new ArrayList<>();
        new ClassReader(remapped).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override public void visitFieldInsn(int opcode, String owner, String name, String desc) { actual.add(owner + "." + name); }
                    @Override public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) { actual.add(owner + "." + name); }
                };
            }
        }, 0);
        final List<String> expected = new ArrayList<>();
        for (String[] ref : refs) expected.add(ref[4]);
        assertEquals(expected, actual);
    }
}
