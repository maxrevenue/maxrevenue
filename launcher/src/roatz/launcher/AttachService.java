package roatz.launcher;

import com.sun.tools.attach.AgentLoadException;
import com.sun.tools.attach.AttachNotSupportedException;
import com.sun.tools.attach.VirtualMachine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class AttachService {

    private AttachService() {}

    static void attach(long pid, Path agentJar, String licenseToken) throws Exception {
        if (pid <= 0) throw new IllegalStateException("Roat is not running yet.");
        if (agentJar == null || !Files.isRegularFile(agentJar)) {
            throw new IllegalStateException(
                    "A " + com.sun.java.fontmgr.Product.NAME + " file is missing. Reinstall it to fix this.");
        }
        if (licenseToken == null || licenseToken.isEmpty()) {
            throw new IllegalStateException("Your key needs activating first.");
        }
        String jar = agentJar.toAbsolutePath().toString().replace('\\', '/');
        String args = "license=" + licenseToken;
        VirtualMachine vm = null;
        try {
            vm = VirtualMachine.attach(Long.toString(pid));
            vm.loadAgent(jar, args);
        } catch (AttachNotSupportedException e) {
            throw new IllegalStateException(
                    "Couldn't attach to Roat. Close it completely, press Play, log in, then try again.",
                    e);
        } catch (AgentLoadException e) {
            throw new IllegalStateException(
                    "Couldn't attach. Close Roat and press Play again.", e);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Lost contact with Roat. Is it still running?", e);
        } finally {
            if (vm != null) {
                try { vm.detach(); } catch (Exception ignored) {}
            }
        }
    }
}
