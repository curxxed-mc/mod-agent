package net.curxxed.dev.agent;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import org.objectweb.asm.ClassReader;

final class BadlionFrameResources extends ClassLoader {
    BadlionFrameResources(ClassLoader gameLoader) {
        super(gameLoader);
    }

    @Override
    public InputStream getResourceAsStream(String resource) {
        InputStream original = getParent() == null ? ClassLoader.getSystemResourceAsStream(resource)
                : getParent().getResourceAsStream(resource);
        if (!resource.endsWith(".class")) return original;
        String name = resource.substring(0, resource.length() - ".class".length());
        byte[] bytes = null;
        if (original != null) {
            try (InputStream input = original) {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                bytes = output.toByteArray();
                if (name.equals(new ClassReader(bytes).getClassName())) {
                    return new ByteArrayInputStream(bytes);
                }
            } catch (IOException | RuntimeException failure) {
                AgentLog.log("ASM frame resource requires runtime metadata: " + name);
            }
        }
        byte[] metadata = BadlionFinalStage.runtimeMetadata(name, getParent(), true);
        if (metadata != null) return new ByteArrayInputStream(metadata);
        return bytes == null ? null : new ByteArrayInputStream(bytes);
    }
}
