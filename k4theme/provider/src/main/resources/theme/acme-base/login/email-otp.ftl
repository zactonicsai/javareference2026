<#--
  email-otp.ftl -- form of the custom "Email OTP (Acme)" authenticator.

  Rendered by EmailOtpAuthenticator via context.form().createForm("email-otp.ftl").

  Contract with the Java code (keep these when you override this file):
    * POST to ${url.loginAction}
    * input  name="otp"     -> the code typed by the user
    * input  name="resend"  -> present when the user asks for a new code
  Attributes supplied by the authenticator:
    * ${emailOtpMaskedEmail}  e.g. a***@acme.test
    * ${emailOtpTtlMinutes}   lifetime of the code in minutes

  HOW TO OVERRIDE: copy this file to <your-theme>/login/email-otp.ftl in a theme
  whose theme.properties says parent=acme-base, then edit the copy.

  The imports below resolve through the parent chain, so template.ftl, field.ftl
  and buttons.ftl come from keycloak.v2 unless a child theme overrides them.
-->
<#import "template.ftl" as layout>
<#import "field.ftl" as field>
<#import "buttons.ftl" as buttons>
<#-- displayMessage=false while the error is shown under the field, so it is not printed twice. -->
<@layout.registrationLayout displayMessage=!messagesPerField.existsError('otp'); section>
<!-- template: email-otp.ftl (acme-base) -->

    <#if section = "header">
        ${msg("emailOtpTitle")}
    <#elseif section = "form">
        <p id="acme-email-otp-instruction" class="acme-instruction">
            ${msg("emailOtpInstruction", emailOtpMaskedEmail, emailOtpTtlMinutes)}
        </p>

        <form id="kc-email-otp-form" class="${properties.kcFormClass!}" onsubmit="login.disabled = true; return true;"
              action="${url.loginAction}" method="post">
            <@field.input name="otp" label=msg("emailOtpLabel") autocomplete="one-time-code" autofocus=true />
            <@buttons.loginButton />
        </form>

        <#-- Separate form so that "resend" never submits a half-typed code. -->
        <form id="kc-email-otp-resend-form" class="${properties.kcFormClass!}" action="${url.loginAction}" method="post">
            <input type="hidden" name="resend" value="true"/>
            <@buttons.actionGroup>
                <@buttons.button id="kc-email-otp-resend" label="emailOtpResend" type="secondary" />
            </@buttons.actionGroup>
        </form>
    </#if>
</@layout.registrationLayout>
