// Mod-Agent Java launcher (Windows). Generated and compiled on this machine by
// `java -jar <agent>.jar setup-badlion` using the .NET Framework compiler built into Windows.
// Reads java.conf beside this executable, adds the agent as the LAST JVM option (so it loads
// after any native agent already on the command line) and runs the real java.exe/javaw.exe.
// Original arguments are forwarded as their exact raw command-line text; version/help probes
// pass through unchanged. The game JVM is tied to this process with a kill-on-close job.
using System;
using System.Collections.Generic;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;

static class ModAgentJavaLauncher
{
    sealed class LauncherException : Exception
    {
        public LauncherException(string message) : base(message) { }
    }

    struct Argument
    {
        public int Start;
        public string Value;
    }

    static string runtime, agent, config, log;
    static readonly List<string> options = new List<string>();

    static int Main()
    {
        try { return Run(); }
        catch (Exception failure)
        {
            try { Console.Error.WriteLine("[Mod-Agent Launcher] " + failure.Message); } catch (Exception) { }
            return 126;
        }
    }

    static int Run()
    {
        string self = Path.GetFullPath(System.Reflection.Assembly.GetEntryAssembly().Location);
        string directory = Path.GetDirectoryName(self);
        ReadConfiguration(Path.Combine(directory, "java.conf"));
        if (runtime == null || !Path.IsPathRooted(runtime))
            throw new LauncherException("runtime= must be the absolute bin directory of the real Java");
        string real = Path.GetFullPath(Path.Combine(runtime, Path.GetFileName(self)));
        if (!File.Exists(real)) throw new LauncherException("runtime= does not contain " + Path.GetFileName(self));
        if (string.Equals(real, self, StringComparison.OrdinalIgnoreCase))
            throw new LauncherException("runtime= points back to this launcher");
        if (log != null && !Path.IsPathRooted(log)) throw new LauncherException("log= must be an absolute file path");

        string raw = Marshal.PtrToStringUni(GetCommandLineW());
        List<Argument> arguments = Tokenize(raw);
        string forwarded;
        bool injected = !IsProbe(arguments);
        if (!injected)
        {
            forwarded = arguments.Count == 0 ? "" : raw.Substring(arguments[0].Start);
        }
        else
        {
            forwarded = Inject(raw, arguments);
        }
        string commandLine = "\"" + real + "\"" + (forwarded.Length == 0 ? "" : " " + forwarded);
        return Execute(real, commandLine, injected);
    }

    static void ReadConfiguration(string path)
    {
        string[] lines;
        try { lines = File.ReadAllLines(path, new UTF8Encoding(false)); }
        catch (Exception) { throw new LauncherException("Cannot read java.conf beside the launcher"); }
        foreach (string original in lines)
        {
            string line = original.Trim();
            if (line.Length == 0 || line[0] == '#') continue;
            int separator = line.IndexOf('=');
            if (separator < 0) throw new LauncherException("Configuration lines must use key=value");
            string key = line.Substring(0, separator).Trim();
            string value = line.Substring(separator + 1).Trim();
            if (value.Length == 0) throw new LauncherException("Empty configuration value");
            switch (key)
            {
                case "runtime": runtime = Assign(runtime, value); break;
                case "agent": agent = Assign(agent, value); break;
                case "config": config = Assign(config, value); break;
                case "log": log = Assign(log, value); break;
                case "vm_option":
                    if (!value.StartsWith("-D") || value.Length < 3)
                        throw new LauncherException("vm_option must be a system property (-D...)");
                    options.Add(value);
                    break;
                default: throw new LauncherException("Unknown configuration key");
            }
        }
    }

    static string Assign(string current, string value)
    {
        if (current != null) throw new LauncherException("Duplicate configuration key");
        return value;
    }

    // Splits the raw command line with Microsoft C runtime rules, remembering where each
    // argument starts so untouched arguments can be forwarded verbatim.
    static List<Argument> Tokenize(string raw)
    {
        List<Argument> result = new List<Argument>();
        int index = 0;
        if (index < raw.Length && raw[index] == '"')
        {
            int close = raw.IndexOf('"', index + 1);
            index = close < 0 ? raw.Length : close + 1;
        }
        while (index < raw.Length && raw[index] != ' ' && raw[index] != '\t') index++;
        while (true)
        {
            while (index < raw.Length && (raw[index] == ' ' || raw[index] == '\t')) index++;
            if (index >= raw.Length) return result;
            Argument argument = new Argument();
            argument.Start = index;
            StringBuilder value = new StringBuilder();
            bool quoted = false;
            while (index < raw.Length && (quoted || (raw[index] != ' ' && raw[index] != '\t')))
            {
                int slashes = 0;
                while (index < raw.Length && raw[index] == '\\') { slashes++; index++; }
                if (index < raw.Length && raw[index] == '"')
                {
                    value.Append('\\', slashes / 2);
                    if (slashes % 2 == 1) { value.Append('"'); index++; }
                    else if (quoted && index + 1 < raw.Length && raw[index + 1] == '"') { value.Append('"'); index += 2; }
                    else { quoted = !quoted; index++; }
                }
                else
                {
                    value.Append('\\', slashes);
                    if (index < raw.Length && (quoted || (raw[index] != ' ' && raw[index] != '\t')))
                    {
                        value.Append(raw[index]);
                        index++;
                    }
                }
            }
            argument.Value = value.ToString();
            result.Add(argument);
        }
    }

    static bool TakesOperand(string value)
    {
        return value == "-cp" || value == "-classpath" || value == "--class-path"
                || value == "-p" || value == "--module-path";
    }

    static bool StartsApplication(string value)
    {
        return value == "-jar" || value == "-m" || value == "--module" || value.StartsWith("--module=")
                || !value.StartsWith("-");
    }

    static bool IsProbe(List<Argument> arguments)
    {
        if (arguments.Count == 0) return true;
        for (int i = 0; i < arguments.Count; i++)
        {
            string value = arguments[i].Value;
            if (value == "-version" || value == "--version" || value == "-fullversion" || value == "-help"
                    || value == "--help" || value == "-?" || value == "-X" || value == "--help-extra") return true;
            if (TakesOperand(value)) { i++; continue; }
            if (StartsApplication(value)) return false;
        }
        return false;
    }

    static string Inject(string raw, List<Argument> arguments)
    {
        if (agent == null) throw new LauncherException("agent= is required for game launches");
        if (!Path.IsPathRooted(agent) || !File.Exists(agent)) throw new LauncherException("agent= must name an existing absolute JAR path");
        if (agent.IndexOf('=') >= 0) throw new LauncherException("Agent JAR path cannot contain '='");
        if (config != null && (!Path.IsPathRooted(config) || !File.Exists(config)))
            throw new LauncherException("config= must name an existing absolute file path");
        string agentOption = "-javaagent:" + agent + (config == null ? "" : "=" + config);
        List<string> owned = new List<string>();
        owned.Add(agentOption);
        owned.AddRange(options);
        StringBuilder ownedText = new StringBuilder();
        foreach (string option in owned) ownedText.Append(Quote(option)).Append(' ');

        StringBuilder result = new StringBuilder();
        for (int i = 0; i < arguments.Count; i++)
        {
            string value = arguments[i].Value;
            if (owned.Contains(value)) continue;
            if (value.StartsWith("-javaagent:" + agent, StringComparison.OrdinalIgnoreCase))
            {
                string rest = value.Substring(("-javaagent:" + agent).Length);
                if (rest.Length == 0 || rest[0] == '=')
                    throw new LauncherException("Conflicting configuration for this Java agent");
            }
            if (TakesOperand(value) && i + 1 < arguments.Count)
            {
                result.Append(Raw(raw, arguments, i)).Append(' ').Append(Raw(raw, arguments, i + 1)).Append(' ');
                i++;
                continue;
            }
            if (StartsApplication(value))
            {
                return result.Append(ownedText).Append(raw.Substring(arguments[i].Start)).ToString();
            }
            result.Append(Raw(raw, arguments, i)).Append(' ');
        }
        return result.Append(ownedText).ToString().TrimEnd(' ');
    }

    static string Raw(string raw, List<Argument> arguments, int index)
    {
        int end = index + 1 < arguments.Count ? arguments[index + 1].Start : raw.Length;
        return raw.Substring(arguments[index].Start, end - arguments[index].Start).TrimEnd(' ', '\t');
    }

    static string Quote(string value)
    {
        if (value.Length > 0 && value.IndexOfAny(new char[] { ' ', '\t', '"' }) < 0) return value;
        StringBuilder result = new StringBuilder("\"");
        int slashes = 0;
        foreach (char character in value)
        {
            if (character == '\\') { slashes++; continue; }
            if (character == '"') { result.Append('\\', slashes * 2 + 1).Append('"'); slashes = 0; continue; }
            result.Append('\\', slashes).Append(character);
            slashes = 0;
        }
        return result.Append('\\', slashes * 2).Append('"').ToString();
    }

    static int Execute(string real, string commandLine, bool injected)
    {
        try { Console.CancelKeyPress += delegate(object sender, ConsoleCancelEventArgs e) { e.Cancel = true; }; }
        catch (Exception) { }

        STARTUPINFO startup = new STARTUPINFO();
        GetStartupInfoW(out startup);
        startup.cb = Marshal.SizeOf(typeof(STARTUPINFO));
        startup.lpReserved = IntPtr.Zero;
        startup.cbReserved2 = 0;
        startup.lpReserved2 = IntPtr.Zero;
        startup.dwFlags = (startup.dwFlags & ~STARTF_USEHOTKEY) | STARTF_USESTDHANDLES;
        startup.hStdInput = GetStdHandle(STD_INPUT_HANDLE);
        startup.hStdOutput = GetStdHandle(STD_OUTPUT_HANDLE);
        startup.hStdError = GetStdHandle(STD_ERROR_HANDLE);

        PROCESS_INFORMATION process;
        StringBuilder mutable = new StringBuilder(commandLine);
        if (!CreateProcessW(real, mutable, IntPtr.Zero, IntPtr.Zero, true, CREATE_SUSPENDED,
                IntPtr.Zero, null, ref startup, out process))
        {
            throw new LauncherException("Cannot execute Java (error " + Marshal.GetLastWin32Error() + ")");
        }
        IntPtr job = KillOnCloseJob();
        if (job != IntPtr.Zero) AssignProcessToJobObject(job, process.hProcess);
        ResumeThread(process.hThread);
        CloseHandle(process.hThread);
        LogLaunch(process.dwProcessId, injected);
        WaitForSingleObject(process.hProcess, INFINITE);
        uint code;
        GetExitCodeProcess(process.hProcess, out code);
        CloseHandle(process.hProcess);
        return unchecked((int)code);
    }

    static IntPtr KillOnCloseJob()
    {
        IntPtr job = CreateJobObjectW(IntPtr.Zero, null);
        if (job == IntPtr.Zero) return IntPtr.Zero;
        JOBOBJECT_EXTENDED_LIMIT_INFORMATION limits = new JOBOBJECT_EXTENDED_LIMIT_INFORMATION();
        // Only the game JVM is tied to this launcher; processes it starts are not.
        limits.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE | JOB_OBJECT_LIMIT_SILENT_BREAKAWAY_OK;
        int size = Marshal.SizeOf(typeof(JOBOBJECT_EXTENDED_LIMIT_INFORMATION));
        IntPtr buffer = Marshal.AllocHGlobal(size);
        try
        {
            Marshal.StructureToPtr(limits, buffer, false);
            if (!SetInformationJobObject(job, JobObjectExtendedLimitInformation, buffer, (uint)size))
            {
                CloseHandle(job);
                return IntPtr.Zero;
            }
        }
        finally { Marshal.FreeHGlobal(buffer); }
        return job;
    }

    static void LogLaunch(uint pid, bool injected)
    {
        if (log == null) return;
        try
        {
            using (FileStream stream = new FileStream(log, FileMode.Append, FileAccess.Write, FileShare.ReadWrite))
            using (StreamWriter writer = new StreamWriter(stream, new UTF8Encoding(false)))
            {
                writer.Write(DateTime.UtcNow.ToString("yyyy-MM-dd'T'HH:mm:ss'Z'")
                        + " pid=" + pid + " agent_added=" + (injected ? "true" : "false") + "\n");
            }
        }
        catch (Exception) { }
    }

    const int STARTF_USESTDHANDLES = 0x100, STARTF_USEHOTKEY = 0x200;
    const int STD_INPUT_HANDLE = -10, STD_OUTPUT_HANDLE = -11, STD_ERROR_HANDLE = -12;
    const uint CREATE_SUSPENDED = 0x4, INFINITE = 0xFFFFFFFF;
    const uint JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE = 0x2000, JOB_OBJECT_LIMIT_SILENT_BREAKAWAY_OK = 0x1000;
    const int JobObjectExtendedLimitInformation = 9;

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    struct STARTUPINFO
    {
        public int cb;
        public IntPtr lpReserved, lpDesktop, lpTitle;
        public int dwX, dwY, dwXSize, dwYSize, dwXCountChars, dwYCountChars, dwFillAttribute, dwFlags;
        public short wShowWindow, cbReserved2;
        public IntPtr lpReserved2, hStdInput, hStdOutput, hStdError;
    }

    [StructLayout(LayoutKind.Sequential)]
    struct PROCESS_INFORMATION
    {
        public IntPtr hProcess, hThread;
        public uint dwProcessId, dwThreadId;
    }

    [StructLayout(LayoutKind.Sequential)]
    struct JOBOBJECT_BASIC_LIMIT_INFORMATION
    {
        public long PerProcessUserTimeLimit, PerJobUserTimeLimit;
        public uint LimitFlags;
        public UIntPtr MinimumWorkingSetSize, MaximumWorkingSetSize;
        public uint ActiveProcessLimit;
        public UIntPtr Affinity;
        public uint PriorityClass, SchedulingClass;
    }

    [StructLayout(LayoutKind.Sequential)]
    struct IO_COUNTERS
    {
        public ulong ReadOperationCount, WriteOperationCount, OtherOperationCount;
        public ulong ReadTransferCount, WriteTransferCount, OtherTransferCount;
    }

    [StructLayout(LayoutKind.Sequential)]
    struct JOBOBJECT_EXTENDED_LIMIT_INFORMATION
    {
        public JOBOBJECT_BASIC_LIMIT_INFORMATION BasicLimitInformation;
        public IO_COUNTERS IoInfo;
        public UIntPtr ProcessMemoryLimit, JobMemoryLimit, PeakProcessMemoryUsed, PeakJobMemoryUsed;
    }

    [DllImport("kernel32.dll")] static extern IntPtr GetCommandLineW();
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode)] static extern void GetStartupInfoW(out STARTUPINFO info);
    [DllImport("kernel32.dll")] static extern IntPtr GetStdHandle(int handle);
    [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    static extern bool CreateProcessW(string application, StringBuilder commandLine, IntPtr processAttributes,
            IntPtr threadAttributes, bool inheritHandles, uint flags, IntPtr environment, string directory,
            ref STARTUPINFO startup, out PROCESS_INFORMATION process);
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode)] static extern IntPtr CreateJobObjectW(IntPtr attributes, string name);
    [DllImport("kernel32.dll")] static extern bool SetInformationJobObject(IntPtr job, int infoClass, IntPtr info, uint length);
    [DllImport("kernel32.dll")] static extern bool AssignProcessToJobObject(IntPtr job, IntPtr process);
    [DllImport("kernel32.dll")] static extern uint ResumeThread(IntPtr thread);
    [DllImport("kernel32.dll")] static extern uint WaitForSingleObject(IntPtr handle, uint milliseconds);
    [DllImport("kernel32.dll")] static extern bool GetExitCodeProcess(IntPtr process, out uint code);
    [DllImport("kernel32.dll")] static extern bool CloseHandle(IntPtr handle);
}
