<#--
  =============================================================================
  login.ftl -- LOCAL OVERRIDE of the username/password screen
  =============================================================================
  Theme chain:  acme-local (this file)  ->  acme-base (jar)  ->  keycloak.v2  ->  base

  Neither acme-base nor this theme would need a login.ftl: without this file
  Keycloak would use keycloak.v2's. Because the file exists HERE, it shadows the
  parents' version for every realm whose login theme is "acme-local".

  This is a copy of theme/keycloak.v2/login/login.ftl from Keycloak 26.8.2 with
  the changes marked "ACME-LOCAL". Keeping the copy otherwise identical makes the
  next upgrade a simple diff against the new original.

  Things worth knowing when editing:
    * A template override replaces the WHOLE file; there is no partial override.
      (Texts and properties, in contrast, are merged key by key.)
    * <#import> also resolves through the chain. "template.ftl" (page skeleton),
      "field.ftl" and "buttons.ftl" below come from keycloak.v2; drop a file of
      the same name into this folder to override the layout for ALL screens.
    * ${msg("key")}         -> messages/messages_<locale>.properties (merged)
      ${properties.key}     -> theme.properties (merged)
      ${url.resourcesPath}  -> this theme's resources/ folder
    * Keep the form contract intact: POST to ${url.loginAction} with the fields
      "username", "password" and "credentialId", or the server-side
      authenticator will not understand the request.
    * To undo the override, delete this file. The parent's screen is back at once.
  =============================================================================
-->
<#import "template.ftl" as layout>
<#import "field.ftl" as field>
<#import "buttons.ftl" as buttons>
<#import "social-providers.ftl" as identityProviders>
<#import "passkeys.ftl" as passkeys>
<@layout.registrationLayout displayMessage=!messagesPerField.existsError('username','password') displayInfo=realm.password && realm.registrationAllowed && !registrationDisabled??; section>
<!-- template: login.ftl (acme-local override) -->

    <#if section = "header">
        <#-- Unchanged markup: the text differs because acme-local/messages overrides the key. -->
        ${msg("loginAccountTitle")}
    <#elseif section = "form">
        <#-- ACME-LOCAL begin: environment banner, driven by a custom theme property. -->
        <#if properties.acmeEnvironmentLabel?has_content>
            <div id="acme-env-banner" class="acme-env-banner" role="note">
                ${msg("acmeEnvironmentNotice", properties.acmeEnvironmentLabel)}
            </div>
        </#if>
        <#-- ACME-LOCAL end -->
        <div id="kc-form">
        AAAAA
          <div id="kc-form-wrapper">
          AAAA A A A A A
            <#if realm.password>
                <form id="kc-form-login" class="${properties.kcFormClass!}" onsubmit="login.disabled = true; return true;" action="${url.loginAction}" method="post" novalidate="novalidate">
                    <#if !usernameHidden??>
                        <#assign label>
                            <#if !realm.loginWithEmailAllowed>${msg("username")}<#elseif !realm.registrationEmailAsUsername>${msg("usernameOrEmail")}<#else>${msg("email")}</#if>
                        </#assign>
                        <@field.input name="username" label=label error=messagesPerField.getFirstError('username','password')
                            autofocus=true autocomplete="${(enableWebAuthnConditionalUI?has_content)?then('username webauthn', 'username')}" value=login.username!'' />
                        <@field.password name="password" label=msg("password") error="" forgotPassword=realm.resetPasswordAllowed autofocus=usernameHidden?? autocomplete="current-password">
                            <#if realm.rememberMe && !usernameHidden??>
                                <@field.checkbox name="rememberMe" label=msg("rememberMe") value=login.rememberMe?? />
                            </#if>
                        </@field.password>
                    <#else>
                        <@field.password name="password" label=msg("password") forgotPassword=realm.resetPasswordAllowed autofocus=usernameHidden?? autocomplete="current-password">
                            <#if realm.rememberMe && !usernameHidden??>
                                <@field.checkbox name="rememberMe" label=msg("rememberMe") value=login.rememberMe?? />
                            </#if>
                        </@field.password>
                    </#if>

                    <input type="hidden" id="id-hidden-input" name="credentialId" <#if auth.selectedCredential?has_content>value="${auth.selectedCredential}"</#if>/>
                    <@buttons.loginButton />
                </form>
            </#if>
            </div>
        </div>
        <@passkeys.conditionalUIData />
        <#-- ACME-LOCAL begin: help line; the address is a theme property overridden in this theme. -->
        <p id="acme-help" class="acme-help">
            ${msg("acmeNeedHelp", properties.acmeSupportEmail!"")}
        </p>
        <#-- ACME-LOCAL end -->
    <#elseif section = "socialProviders" >
        <#if realm.password && social.providers?? && social.providers?has_content>
            <@identityProviders.show social=social/>
        </#if>
    <#elseif section = "info" >
        <#if realm.password && realm.registrationAllowed && !registrationDisabled??>
            <div id="kc-registration-container">
                <div id="kc-registration">
                    <span>${msg("noAccount")} <a href="${url.registrationUrl}">${msg("doRegister")}</a></span>
                </div>
            </div>
        </#if>
    </#if>

</@layout.registrationLayout>
