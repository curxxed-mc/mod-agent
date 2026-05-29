package net.curxxed.dev.agent.config;

import java.util.Objects;

/**
 * jar = Path to mod.
 *
 * mixin = Mixins json for that mod (if they have one).
 *
 * property = Injected property.
 *
 * asm = Transformer if they have one.
 *
 * exclude = Mapping & ASM exclusions, similar to forge's "@IFMLLoadingPlugin.TransformerExclusions".
 * Should generally be used for shaded libs and everything that shouldn't be remapped.
 * Separated with ,
 * Example: wtf/tatp/meowtils/asm,wtf/tatp/meowtils/libs
 */
public class ModEntry {

    private final String jar;
    private final String mixin;
    private final String property;
    private final String asm;
    private final String exclude;

    public ModEntry(String jar, String mixin, String property, String asm, String exclude) {
        this.jar = jar;
        this.mixin = mixin;
        this.property = property;
        this.asm = asm;
        this.exclude = exclude;
    }

    public String getJar() {
        return jar;
    }

    public String getMixin() {
        return mixin;
    }

    public String getProperty() {
        return property;
    }

    public String getAsm() {
        return asm;
    }

    public String getExclude() {
        return exclude;
    }

    @Override
    public String toString() {
        return "ModEntry{" +
                "jar='" + jar + '\'' +
                ", mixin='" + mixin + '\'' +
                ", property='" + property + '\'' +
                ", asm='" + asm + '\"' +
                ", exclude='" + exclude + '\"' +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ModEntry)) return false;

        final ModEntry modEntry = (ModEntry) o;

        if (!Objects.equals(jar, modEntry.jar)) return false;
        if (!Objects.equals(mixin, modEntry.mixin)) return false;
        if (!Objects.equals(property, modEntry.property)) return false;
        if (!Objects.equals(asm, modEntry.asm)) return false;
        return Objects.equals(exclude, modEntry.exclude);
    }

    @Override
    public int hashCode() {
        return Objects.hash(jar, mixin, property, asm, exclude);
    }
}