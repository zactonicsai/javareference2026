package com.acme.keycloak.auth;

import java.util.List;

import org.keycloak.Config;
import org.keycloak.authentication.Authenticator;
import org.keycloak.authentication.AuthenticatorFactory;
import org.keycloak.models.AuthenticationExecutionModel.Requirement;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.provider.ProviderConfigurationBuilder;

/**
 * Registers {@link EmailOtpAuthenticator} with Keycloak.
 *
 * <p>Keycloak discovers this class through Java's ServiceLoader: its fully qualified name is
 * listed in {@code META-INF/services/org.keycloak.authentication.AuthenticatorFactory}.
 * Without that file the jar is loaded but the authenticator never shows up.
 *
 * <p>After deployment the step appears as "Email OTP (Acme)" under
 * Authentication &rarr; Flows &rarr; (a flow) &rarr; Add step.
 */
public class EmailOtpAuthenticatorFactory implements AuthenticatorFactory {

    /**
     * Unique id of the provider. It is stored in the database and in realm exports
     * ("authenticator": "acme-email-otp"), so treat it as a public contract: never rename it.
     */
    public static final String PROVIDER_ID = "acme-email-otp";

    // Keys of the per-execution settings (gear icon next to the step in the admin console).
    static final String CONF_CODE_LENGTH = "codeLength";
    static final String CONF_TTL = "ttlSeconds";
    static final String CONF_MAX_ATTEMPTS = "maxAttempts";
    static final String CONF_RESEND_COOLDOWN = "resendCooldownSeconds";

    /** The authenticator is stateless, so a single shared instance is safe and cheapest. */
    private static final EmailOtpAuthenticator SINGLETON = new EmailOtpAuthenticator();

    private static final List<ProviderConfigProperty> CONFIG_PROPERTIES = ProviderConfigurationBuilder.create()
            .property().name(CONF_CODE_LENGTH).label("Code length")
                .helpText("Number of digits in the code (4-10).")
                .type(ProviderConfigProperty.STRING_TYPE).defaultValue("6").add()
            .property().name(CONF_TTL).label("Code lifetime (seconds)")
                .helpText("How long a code stays valid (30-3600).")
                .type(ProviderConfigProperty.STRING_TYPE).defaultValue("300").add()
            .property().name(CONF_MAX_ATTEMPTS).label("Max attempts")
                .helpText("Wrong guesses allowed before the code is invalidated (1-10).")
                .type(ProviderConfigProperty.STRING_TYPE).defaultValue("3").add()
            .property().name(CONF_RESEND_COOLDOWN).label("Resend cooldown (seconds)")
                .helpText("Minimum time between two e-mails to the same login attempt (0-3600).")
                .type(ProviderConfigProperty.STRING_TYPE).defaultValue("30").add()
            .build();

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public Authenticator create(KeycloakSession session) {
        return SINGLETON;
    }

    /** Name shown in the admin console's "Add step" dialog. */
    @Override
    public String getDisplayType() {
        return "Email OTP (Acme)";
    }

    @Override
    public String getHelpText() {
        return "Sends a one-time code to the user's e-mail address and requires it to continue.";
    }

    /** Groups the step with other one-time-code mechanisms (affects the reported ACR/AMR category). */
    @Override
    public String getReferenceCategory() {
        return "otp";
    }

    @Override
    public boolean isConfigurable() {
        return true;
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        return CONFIG_PROPERTIES;
    }

    /** Requirement values an admin may choose for this step. */
    @Override
    public Requirement[] getRequirementChoices() {
        return new Requirement[] {Requirement.REQUIRED, Requirement.ALTERNATIVE, Requirement.DISABLED};
    }

    /** There is no credential the user could enrol on their own, so no "set up" required action. */
    @Override
    public boolean isUserSetupAllowed() {
        return false;
    }

    /** Server-level (not realm-level) settings would be read here from keycloak.conf / env vars. */
    @Override
    public void init(Config.Scope config) {
        // no server-level configuration
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
        // nothing to wire up
    }

    @Override
    public void close() {
        // nothing to release
    }
}
