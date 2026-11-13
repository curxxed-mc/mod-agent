package dev.curxxed.agent;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.security.ProtectionDomain;
import dev.curxxed.agent.enums.Environment;
import dev.curxxed.agent.mappings.MappingRegistry;

final class BadlionCompatibilityInspection {
    static void start(Instrumentation instrumentation) {
        if (!BadlionBootstrapFallback.isBadlionLaunch(System.getProperty("sun.java.command", ""))) {
            AgentLog.log("Badlion inspection requires the Badlion launch entrypoint; no transformers registered.");
            return;
        }
        String runtime = ManagementFactory.getRuntimeMXBean().getName();
        int separator = runtime.indexOf('@');
        AgentLog.log("Badlion inspection pid=" + (separator < 0 ? "unknown" : runtime.substring(0, separator)));
        BadlionFinalStage.setInstrumentation(instrumentation);
        BadlionFinalStage.register(observer(AgentBootstrap.loadMappings()));
        BadlionFinalStage.install();
        AgentLog.log("Badlion inspection active=" + BadlionFinalStage.isInstalled()
                + "; no mod registration, bootstrap, Mixin setup, or returned bytecode patches.");
    }

    static ClassFileTransformer observer(MappingRegistry mappings) {
        return new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String name, Class<?> type,
                    ProtectionDomain domain, byte[] bytes) {
                if (name == null || bytes == null) return null;
                if (name.equals("ave") || name.equals("bfk")) {
                    AgentLog.log("Badlion inspection runtime target " + name);
                    AsmFailureDiagnostics.log(name, bytes, mappings, Environment.OBF);
                } else if (name.equals("dev/minecraft/client/Minecraft")
                        || name.equals("dev/minecraft/client/renderer/EntityRenderer")) {
                    AgentLog.log("Badlion inspection runtime target " + name);
                    AsmFailureDiagnostics.log(name, bytes, mappings, Environment.MCP);
                }
                return null;
            }
        };
    }
}
