package dev.curxxed.agent.transformer;

import dev.curxxed.agent.AgentLog;
import dev.curxxed.agent.BadlionFinalStage;
import dev.curxxed.agent.enums.Environment;
import dev.curxxed.agent.mappings.MappingRegistry;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.MethodRemapper;
import org.objectweb.asm.commons.Remapper;
import java.io.*;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;

// Translates mod bytecode from any known mapping namespace (obf, SRG, MCP)
// into the namespace used by the current runtime.
// TODO: Remap enums properly.
public class RuntimeRemapper implements ClassFileTransformer {
    private static final java.util.Map<ClassLoader, Set<String>> METADATA_WARNINGS =
            java.util.Collections.synchronizedMap(new java.util.IdentityHashMap<>());

    private static void warnMetadataOnce(ClassLoader loader, String name, byte[] bytes, RuntimeException failure) {
        synchronized (METADATA_WARNINGS) {
            Set<String> warned = METADATA_WARNINGS.computeIfAbsent(loader, ignored -> new HashSet<>());
            if (!warned.add(name)) return;
        }
        AgentLog.log("Remapper hierarchy metadata unavailable: " + name + " loader=" + loader
                + " resourceBytes=" + (bytes == null ? 0 : bytes.length) + " reason=" + failure
                + "; retaining direct mappings, skipping optional hierarchy lookup");
    }

    private final MappingRegistry mappings;
    private final Supplier<Environment> environmentSupplier;
    private final Set<String> modClassNames;
    private final Set<String> remapLoaders;

    public RuntimeRemapper(Set<String> modClassNames,
                           MappingRegistry mappings,
                           Supplier<Environment> environmentSupplier) {
        this(modClassNames, mappings, environmentSupplier, java.util.Collections.emptySet());
    }

    public RuntimeRemapper(Set<String> modClassNames, MappingRegistry mappings,
                           Supplier<Environment> environmentSupplier, Set<String> remapLoaders) {
        this.modClassNames = modClassNames;
        this.mappings = mappings;
        this.environmentSupplier = environmentSupplier;
        this.remapLoaders = java.util.Collections.unmodifiableSet(new HashSet<>(remapLoaders));
        AgentLog.log("RuntimeRemapper ready: " + mappings.classCount() + " classes, " + mappings.methodCount() + " methods, " + mappings.fieldCount() + " fields.");
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain domain, byte[] classfileBuffer) {
        if (className == null) return null;
        // Optional in-memory plugin loaders are declared by mods, not named in agent code.
        String loaderName = loader != null ? loader.getClass().getName() : "";
        boolean extension = remapLoaders.contains(loaderName);
        if (!modClassNames.contains(className) && !extension) return null;

        Environment environment = environmentSupplier.get();
        Set<String> protectedNames = modClassNames;
        if (extension) {
            protectedNames = new HashSet<>(modClassNames);
            protectedNames.add(className);
        }
        byte[] remapped = remapBytes(classfileBuffer, mappings, environment, protectedNames, loader);
        if (remapped == classfileBuffer) return null;

        AgentLog.log("Remapped " + className + " to " + environment + " runtime names");
        return remapped;
    }

    public static byte[] remapBytes(byte[] bytes,
                                    MappingRegistry mappings,
                                    Environment environment,
                                    Set<String> protectedClassNames,
                                    ClassLoader loader) {
        ClassReader cr = new ClassReader(bytes);
        ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
        NamespaceRemapper remapper = new NamespaceRemapper(
                loader, mappings, environment.namespace(), protectedClassNames);
        cr.accept(new ClassRemapper(cw, remapper), 0);
        return remapper.changed() ? cw.toByteArray() : bytes;
    }

    /** Native clients already chose their runtime names. Translate only ASM additions. */
    public static byte[] remapInjectedBytes(byte[] original, byte[] patched,
                                            MappingRegistry mappings, Environment environment,
                                            Set<String> modClasses, ClassLoader loader) {
        Set<String> originalMethods = new HashSet<>();
        Set<String> originalFrameTypes = new HashSet<>();
        new ClassReader(original).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] exceptions) {
                originalMethods.add(name + desc);
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override public void visitFrame(int type, int locals, Object[] local, int stacks, Object[] stack) {
                        rememberFrame(local, locals);
                        rememberFrame(stack, stacks);
                    }
                    private void rememberFrame(Object[] values, int count) {
                        for (int i = 0; values != null && i < count; i++) {
                            if (values[i] instanceof String) originalFrameTypes.add((String) values[i]);
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG);
        ClassReader reader = new ClassReader(patched);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        NamespaceRemapper remapper = new NamespaceRemapper(loader, mappings, environment.namespace(), modClasses);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] exceptions) {
                if (!originalMethods.contains(name + desc)) {
                    MethodVisitor added = super.visitMethod(access, name, remapper.mapMethodDesc(desc),
                            remapper.mapSignature(sig, false), exceptions == null ? null : remapper.mapTypes(exceptions));
                    return new MethodRemapper(added, remapper);
                }
                MethodVisitor existing = super.visitMethod(access, name, desc, sig, exceptions);
                return new MethodVisitor(Opcodes.ASM9, existing) {
                    @Override public void visitMethodInsn(int opcode, String owner, String method, String descriptor, boolean iface) {
                        // The inserted handler is mod-owned. Original client calls are
                        // deliberately not fed through the namespace remapper.
                        super.visitMethodInsn(opcode, owner, method,
                                modClasses.contains(owner) ? remapper.mapMethodDesc(descriptor) : descriptor, iface);
                    }
                    @Override public void visitFrame(int type, int locals, Object[] local, int stacks, Object[] stack) {
                        super.visitFrame(type, locals, mapNewFrameTypes(local, locals), stacks, mapNewFrameTypes(stack, stacks));
                    }
                    private Object[] mapNewFrameTypes(Object[] values, int count) {
                        if (values == null) return null;
                        Object[] result = values.clone();
                        for (int i = 0; i < count; i++) {
                            if (result[i] instanceof String && !originalFrameTypes.contains(result[i])) {
                                result[i] = remapper.mapType((String) result[i]);
                            }
                        }
                        return result;
                    }
                };
            }
        }, 0);
        return remapper.changed() ? writer.toByteArray() : patched;
    }

    private static class NamespaceRemapper extends Remapper {
        private final ClassLoader loader;
        private final MappingRegistry mappings;
        private final MappingRegistry.Namespace targetNamespace;
        private final Set<String> protectedClassNames;

        // Tracks classes whose superclass chain has already been seeded into the registry
        // so we only pay the getResourceAsStream cost once per unique owner class.
        private final Set<String> seededClasses = new HashSet<>();

        private boolean changed;

        NamespaceRemapper(ClassLoader loader,
                          MappingRegistry mappings,
                          MappingRegistry.Namespace targetNamespace,
                          Set<String> protectedClassNames) {
            this.loader = loader;
            this.mappings = mappings;
            this.targetNamespace = targetNamespace;
            this.protectedClassNames = protectedClassNames;
        }

        boolean changed() {
            return changed;
        }

        @Override
        public String map(String internalName) {
            if (internalName == null || protectedClassNames.contains(internalName)) return internalName;
            if (internalName.startsWith("java/") || internalName.startsWith("javax/") || internalName.startsWith("jdk/") || internalName.startsWith("sun/") || internalName.startsWith("com/sun/")) return internalName;
            String mapped = mappings.mapClass(internalName, targetNamespace);
            if (!mapped.equals(internalName)) changed = true;
            return mapped;
        }

        @Override
        public String mapMethodName(String owner, String name, String descriptor) {
            //if (protectedClassNames.contains(owner)) return name; // TODO: This breaks certain remaps?
            if (owner.startsWith("java/") || owner.startsWith("javax/") || owner.startsWith("jdk/") || owner.startsWith("sun/") || owner.startsWith("com/sun/")) return name;
            seedSuperClassChain(owner);
            String mapped = mappings.mapMethodName(owner, name, descriptor, targetNamespace);
            if (!mapped.equals(name)) changed = true;
            return mapped;
        }

        @Override
        public String mapFieldName(String owner, String name, String descriptor) {
            if (owner.startsWith("java/") || owner.startsWith("javax/") || owner.startsWith("jdk/") || owner.startsWith("sun/") || owner.startsWith("com/sun/")) return name;
            seedSuperClassChain(owner);
            String mapped = mappings.mapFieldName(owner, name, descriptor, targetNamespace);
            if (!mapped.equals(name)) changed = true;
            return mapped;
        }

        // Walk the runtime class hierarchy rooted at internalName and register every
        // parent relationship into MappingRegistry.superClasses so that findMethod's
        // while-loop chain walk can follow them.
        //
        // Prefer observed/loaded hierarchy metadata for protected native clients.
        // Missing or non-class resources must not discard the transformed game class.
        // The primary fix for the inherited-method case is in MappingRegistry.findMethod
        // via the SRG-name direct lookup.
        private void seedSuperClassChain(String internalName) {
            if (internalName == null || !seededClasses.add(internalName)) return;

            ClassInfo info = readClassInfo(internalName);
            if (info == null) return;

            if (info.superName != null && !"java/lang/Object".equals(info.superName)) {
                mappings.registerSuperClass(internalName, info.superName);
                seedSuperClassChain(info.superName);
            }
            for (String iface : info.interfaces) {
                mappings.registerSuperClass(internalName, iface);
                seedSuperClassChain(iface);
            }
        }

        private ClassInfo readClassInfo(String internalName) {
            if (BadlionFinalStage.isInstalled()) {
                byte[] runtime = BadlionFinalStage.runtimeMetadata(internalName, loader, false);
                if (runtime != null) return parseClassInfo(internalName, runtime);
            }
            byte[] bytes = readClassBytes(internalName);
            if (bytes == null) return null;
            try {
                return parseClassInfo(internalName, bytes);
            } catch (RuntimeException failure) {
                // Resources supplied by protected loaders need not be class files.
                // This lookup is optional; direct mappings still remain valid.
                warnMetadataOnce(loader, internalName, bytes, failure);
                return null;
            }
        }

        private ClassInfo parseClassInfo(String internalName, byte[] bytes) {
            if (bytes.length < 10 || bytes[0] != (byte) 0xca || bytes[1] != (byte) 0xfe
                    || bytes[2] != (byte) 0xba || bytes[3] != (byte) 0xbe) {
                throw new IllegalArgumentException("Resource is not a class file");
            }
            ClassReader cr = new ClassReader(bytes);
            if (!internalName.equals(cr.getClassName())) {
                throw new IllegalArgumentException("Unexpected resource class: " + cr.getClassName());
            }
            return new ClassInfo(cr.getSuperName(), cr.getInterfaces());
        }

        private byte[] readClassBytes(String internalName) {
            String resource = internalName + ".class";
            try {
                InputStream is = loader != null ? loader.getResourceAsStream(resource) : null;
                if (is == null) is = ClassLoader.getSystemResourceAsStream(resource);
                if (is == null) return null;

                try (InputStream in = is;
                     ByteArrayOutputStream out = new ByteArrayOutputStream()) {

                    byte[] buffer = new byte[4096];
                    int len;

                    while ((len = in.read(buffer)) != -1) {
                        out.write(buffer, 0, len);
                    }

                    return out.toByteArray();
                }

            } catch (Exception ignored) {
                return null;
            }
        }

        private static final class ClassInfo {
            final String superName;
            final String[] interfaces;

            ClassInfo(String superName, String[] interfaces) {
                this.superName = superName;
                this.interfaces = interfaces;
            }
        }
    }
}
