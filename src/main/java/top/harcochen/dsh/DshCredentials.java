package top.harcochen.dsh;

import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.credentialStore.Credentials;
import com.intellij.ide.passwordSafe.PasswordSafe;
import com.intellij.openapi.project.Project;

/** Keeps the API key in IntelliJ's password store instead of project XML or logs. */
public final class DshCredentials {
    private static final String SERVICE = "top.harcochen.dsh.api-key";

    private DshCredentials() {}

    public static void store(Project project, String value) {
        CredentialAttributes attributes = attributes(project);
        PasswordSafe.getInstance().set(attributes, new Credentials("dsh", value));
    }

    public static String read(Project project) {
        Credentials credentials = PasswordSafe.getInstance().get(attributes(project));
        return credentials == null ? null : credentials.getPasswordAsString();
    }

    public static void clear(Project project) {
        PasswordSafe.getInstance().set(attributes(project), null);
    }

    private static CredentialAttributes attributes(Project project) {
        String projectKey = project.getLocationHash();
        return new CredentialAttributes(SERVICE + ":" + projectKey, "dsh", false);
    }
}
