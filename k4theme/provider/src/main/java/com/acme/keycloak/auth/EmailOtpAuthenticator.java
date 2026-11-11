package com.acme.keycloak.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;

import org.jboss.logging.Logger;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.AuthenticationFlowError;
import org.keycloak.authentication.Authenticator;
import org.keycloak.email.EmailException;
import org.keycloak.email.EmailTemplateProvider;
import org.keycloak.forms.login.LoginFormsProvider;
import org.keycloak.models.AuthenticatorConfigModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.utils.FormMessage;
import org.keycloak.sessions.AuthenticationSessionModel;

/**
 * Custom login step: e-mails a one-time code to the user and asks for it.
 *
 * <p>Intended to run AFTER the username/password form, so a user is already
 * attached to the flow (see {@link #requiresUser()}).
 *
 * <h2>How the pieces fit together</h2>
 * <ul>
 *   <li>The form is rendered from the FreeMarker template {@value #FORM_TEMPLATE}. Keycloak
 *       looks that file up in the realm's <b>login theme</b>, walking up the parent chain:
 *       {@code acme-local (folder) -> acme-base (this jar) -> keycloak.v2 -> base}.
 *       The default template ships in this jar under
 *       {@code theme/acme-base/login/email-otp.ftl}; a child theme overrides it simply by
 *       containing a file with the same name.</li>
 *   <li>The e-mail is rendered from {@code html/email-otp.ftl} + {@code text/email-otp.ftl}
 *       of the realm's <b>email theme</b> (also shipped in this jar, theme {@code acme-base}).</li>
 * </ul>
 *
 * <h2>Design notes / best practices applied</h2>
 * <ul>
 *   <li><b>Stateless.</b> One instance serves all requests (see the factory), so there are no
 *       instance fields holding request data. All state lives in the authentication session.</li>
 *   <li><b>Auth notes</b> (server side, never sent to the browser) hold the code, its expiry,
 *       the failed-attempt counter and the last send time.</li>
 *   <li>Codes come from {@link SecureRandom}, are compared in constant time, expire, are
 *       single use, and are invalidated after too many wrong guesses.</li>
 *   <li>Re-sending is rate limited so the form cannot be used to flood a mailbox.</li>
 *   <li>The code itself is never logged.</li>
 * </ul>
 */
public class EmailOtpAuthenticator implements Authenticator {

    private static final Logger LOG = Logger.getLogger(EmailOtpAuthenticator.class);

    /** Login-theme template rendered by this step. Override it in a child theme by file name. */
    static final String FORM_TEMPLATE = "email-otp.ftl";
    /** Email-theme template (resolved as html/email-otp.ftl and text/email-otp.ftl). */
    static final String EMAIL_TEMPLATE = "email-otp.ftl";

    /** Name of the HTML input carrying the code; also used as the field-error key. */
    static final String FIELD_OTP = "otp";
    /** Present in the POST when the user pressed "Send a new code". */
    static final String FIELD_RESEND = "resend";

    // Auth-note keys. Prefixed to avoid clashing with notes of other authenticators.
    private static final String NOTE_CODE = "acme.emailOtp.code";
    private static final String NOTE_EXPIRES_AT = "acme.emailOtp.expiresAt";
    private static final String NOTE_ATTEMPTS = "acme.emailOtp.attempts";
    private static final String NOTE_LAST_SENT_AT = "acme.emailOtp.lastSentAt";

    private static final SecureRandom RANDOM = new SecureRandom();

    // ------------------------------------------------------------------ flow entry points

    /** Called when the flow reaches this step (and again when the user reloads the page). */
    @Override
    public void authenticate(AuthenticationFlowContext context) {
        // Only send a mail if there is no usable code yet; otherwise F5 would spam the inbox.
        if (!hasUsableCode(context.getAuthenticationSession()) && !issueAndSendCode(context)) {
            return; // issueAndSendCode already produced an error response
        }
        context.challenge(form(context).createForm(FORM_TEMPLATE));
    }

    /** Called when the user submits the form rendered by {@link #authenticate}. */
    @Override
    public void action(AuthenticationFlowContext context) {
        MultivaluedMap<String, String> params = context.getHttpRequest().getDecodedFormParameters();

        if (params.containsKey(FIELD_RESEND)) {
            handleResend(context);
            return;
        }

        AuthenticationSessionModel authSession = context.getAuthenticationSession();

        if (!hasUsableCode(authSession)) {
            // Expired (or never issued): send a fresh one and say why.
            if (issueAndSendCode(context)) {
                context.challenge(form(context).setError("emailOtpExpired").createForm(FORM_TEMPLATE));
            }
            return;
        }

        String entered = params.getFirst(FIELD_OTP);
        entered = entered == null ? "" : entered.trim();
        if (entered.isEmpty()) {
            // An empty submit is not a guess; do not count it as a failed attempt.
            context.challenge(fieldError(context, "emailOtpMissing"));
            return;
        }

        if (constantTimeEquals(entered, authSession.getAuthNote(NOTE_CODE))) {
            clearNotes(authSession); // single use
            context.success();
            return;
        }

        int attempts = parseInt(authSession.getAuthNote(NOTE_ATTEMPTS), 0) + 1;
        int maxAttempts = config(context, EmailOtpAuthenticatorFactory.CONF_MAX_ATTEMPTS, 3, 1, 10);

        if (attempts >= maxAttempts) {
            // Burn the code: an attacker must not get unlimited guesses at one code.
            clearNotes(authSession);
            LOG.warnf("Email OTP: too many invalid codes for user %s in realm %s",
                    context.getUser().getId(), context.getRealm().getName());
            context.failureChallenge(AuthenticationFlowError.INVALID_CREDENTIALS,
                    context.form().setError("emailOtpTooManyAttempts")
                            .createErrorPage(Response.Status.UNAUTHORIZED));
            return;
        }

        authSession.setAuthNote(NOTE_ATTEMPTS, Integer.toString(attempts));
        // failureChallenge (not challenge) records a LOGIN_ERROR event for auditing.
        context.failureChallenge(AuthenticationFlowError.INVALID_CREDENTIALS,
                fieldError(context, "emailOtpInvalid"));
    }

    // ------------------------------------------------------------------ SPI contract

    /** This step needs to know who is logging in, so it must come after a step that sets the user. */
    @Override
    public boolean requiresUser() {
        return true;
    }

    /**
     * Whether this step can work for the given user. Without an e-mail address we cannot
     * deliver a code. If the execution is REQUIRED and this returns false the login fails;
     * wrap the step in a conditional sub-flow if some users legitimately have no address.
     */
    @Override
    public boolean configuredFor(KeycloakSession session, RealmModel realm, UserModel user) {
        return user.getEmail() != null && !user.getEmail().isBlank();
    }

    /** Nothing the user could set up themselves (see isUserSetupAllowed() in the factory). */
    @Override
    public void setRequiredActions(KeycloakSession session, RealmModel realm, UserModel user) {
        // no-op
    }

    /** Nothing to release: the authenticator holds no resources. */
    @Override
    public void close() {
        // no-op
    }

    // ------------------------------------------------------------------ helpers

    private void handleResend(AuthenticationFlowContext context) {
        AuthenticationSessionModel authSession = context.getAuthenticationSession();
        int cooldown = config(context, EmailOtpAuthenticatorFactory.CONF_RESEND_COOLDOWN, 30, 0, 3600);
        long lastSent = parseLong(authSession.getAuthNote(NOTE_LAST_SENT_AT), 0L);
        long waitSeconds = cooldown - (nowSeconds() - lastSent);

        if (waitSeconds > 0) {
            context.challenge(form(context).setError("emailOtpResendCooldown", waitSeconds)
                    .createForm(FORM_TEMPLATE));
            return;
        }
        if (issueAndSendCode(context)) {
            context.challenge(form(context).setInfo("emailOtpResent").createForm(FORM_TEMPLATE));
        }
    }

    /**
     * Generates a new code, stores it in the authentication session and e-mails it.
     *
     * @return true on success; false if sending failed, in which case an error response has
     *         already been set on the context and the caller must simply return.
     */
    private boolean issueAndSendCode(AuthenticationFlowContext context) {
        int length = config(context, EmailOtpAuthenticatorFactory.CONF_CODE_LENGTH, 6, 4, 10);
        int ttl = config(context, EmailOtpAuthenticatorFactory.CONF_TTL, 300, 30, 3600);
        String code = generateCode(length);

        AuthenticationSessionModel authSession = context.getAuthenticationSession();
        authSession.setAuthNote(NOTE_CODE, code);
        authSession.setAuthNote(NOTE_EXPIRES_AT, Long.toString(nowSeconds() + ttl));
        authSession.setAuthNote(NOTE_LAST_SENT_AT, Long.toString(nowSeconds()));
        authSession.removeAuthNote(NOTE_ATTEMPTS);

        // Variables available inside the e-mail templates as ${code} and ${ttlMinutes}.
        Map<String, Object> mailAttributes = new HashMap<>();
        mailAttributes.put("code", code);
        mailAttributes.put("ttlMinutes", ttlMinutes(ttl));

        try {
            context.getSession().getProvider(EmailTemplateProvider.class)
                    .setRealm(context.getRealm())
                    .setUser(context.getUser())
                    .setAuthenticationSession(authSession)
                    // subject = message key "emailOtpSubject" from the email theme, {0} = realm name
                    .send("emailOtpSubject", List.of(realmDisplayName(context.getRealm())),
                            EMAIL_TEMPLATE, mailAttributes);
            return true;
        } catch (EmailException e) {
            // Typical causes: realm SMTP settings missing, or the realm's email theme is not
            // acme-base (or a child of it) so email-otp.ftl cannot be found.
            LOG.errorf(e, "Email OTP: could not send code to user %s in realm %s",
                    context.getUser().getId(), context.getRealm().getName());
            clearNotes(authSession);
            context.failureChallenge(AuthenticationFlowError.INTERNAL_ERROR,
                    context.form().setError("emailOtpSendError")
                            .createErrorPage(Response.Status.INTERNAL_SERVER_ERROR));
            return false;
        }
    }

    /** Form pre-filled with the attributes that email-otp.ftl (and overrides of it) may use. */
    private LoginFormsProvider form(AuthenticationFlowContext context) {
        int ttl = config(context, EmailOtpAuthenticatorFactory.CONF_TTL, 300, 30, 3600);
        return context.form()
                .setAttribute("emailOtpMaskedEmail", maskEmail(context.getUser().getEmail()))
                .setAttribute("emailOtpTtlMinutes", ttlMinutes(ttl));
    }

    /** Same form with an error attached to the code input (shown under the field). */
    private Response fieldError(AuthenticationFlowContext context, String messageKey) {
        return form(context).addError(new FormMessage(FIELD_OTP, messageKey)).createForm(FORM_TEMPLATE);
    }

    private static boolean hasUsableCode(AuthenticationSessionModel authSession) {
        return authSession.getAuthNote(NOTE_CODE) != null
                && nowSeconds() < parseLong(authSession.getAuthNote(NOTE_EXPIRES_AT), 0L);
    }

    private static void clearNotes(AuthenticationSessionModel authSession) {
        authSession.removeAuthNote(NOTE_CODE);
        authSession.removeAuthNote(NOTE_EXPIRES_AT);
        authSession.removeAuthNote(NOTE_ATTEMPTS);
        // NOTE_LAST_SENT_AT is kept on purpose so the resend cooldown survives.
    }

    private static String generateCode(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(RANDOM.nextInt(10));
        }
        return sb.toString();
    }

    /** Constant-time comparison so response timing does not leak how many digits matched. */
    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    /** "alice@acme.test" becomes "a***@acme.test": confirms the destination without disclosing it. */
    static String maskEmail(String email) {
        int at = email.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        return email.charAt(0) + "***" + email.substring(at);
    }

    private static String realmDisplayName(RealmModel realm) {
        String displayName = realm.getDisplayName();
        return displayName == null || displayName.isBlank() ? realm.getName() : displayName;
    }

    private static int ttlMinutes(int ttlSeconds) {
        return Math.max(1, Math.round(ttlSeconds / 60f));
    }

    private static long nowSeconds() {
        return System.currentTimeMillis() / 1000L;
    }

    /** Reads an integer setting of this execution, falling back to a default and clamping to a sane range. */
    private static int config(AuthenticationFlowContext context, String key, int defaultValue, int min, int max) {
        AuthenticatorConfigModel model = context.getAuthenticatorConfig();
        String raw = model == null || model.getConfig() == null ? null : model.getConfig().get(key);
        return Math.max(min, Math.min(max, parseInt(raw, defaultValue)));
    }

    private static int parseInt(String raw, int defaultValue) {
        try {
            return raw == null ? defaultValue : Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static long parseLong(String raw, long defaultValue) {
        try {
            return raw == null ? defaultValue : Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
