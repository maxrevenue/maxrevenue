import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.AttachNotSupportedException;
import java.io.IOException;

/**
 * Minimal Dynamic Attach driver: bind PID -> loadAgent -> detach.
 * Exit codes: 0 ok, 2 attach failed, 3 loadAgent failed, 4 detach warn.
 * Silent unless -Dfontmgr.attach.verbose=true
 */
public final class AttachLoader {

    private static boolean verbose() {
        return "true".equalsIgnoreCase(System.getProperty("fontmgr.attach.verbose"));
    }

    private static void say(String msg) {
        if (verbose()) System.out.println(msg);
    }

    private static void err(String msg) {
        if (verbose()) System.err.println(msg);
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            if (verbose()) System.err.println("usage: AttachLoader <pid> <agentJar> [agentArgs]");
            System.exit(1);
        }
        final String pid = args[0];
        final String jar = args[1];
        final String agentArgs = args.length > 2 ? args[2] : null;

        VirtualMachine vm = null;
        try {
            say("[attach] binding pid=" + pid);
            vm = VirtualMachine.attach(pid);
            say("[attach] bound: " + vm);
        } catch (AttachNotSupportedException e) {
            err("[attach] FAIL bind (not supported): " + e.getMessage());
            System.exit(2);
        } catch (IOException e) {
            err("[attach] FAIL bind (I/O / access): " + e.getMessage());
            System.exit(2);
        } catch (Throwable t) {
            err("[attach] FAIL bind: " + t);
            System.exit(2);
        }

        try {
            say("[attach] loadAgent jar=" + jar);
            if (agentArgs == null || agentArgs.isEmpty()) {
                vm.loadAgent(jar);
            } else {
                vm.loadAgent(jar, agentArgs);
            }
            say("[attach] loadAgent OK");
        } catch (Throwable t) {
            err("[attach] FAIL loadAgent: " + t);
            try { vm.detach(); } catch (Throwable ignored) {}
            System.exit(3);
        }

        try {
            vm.detach();
            say("[attach] detached");
            System.exit(0);
        } catch (Throwable t) {
            err("[attach] WARN detach: " + t.getMessage());
            System.exit(4);
        }
    }
}
