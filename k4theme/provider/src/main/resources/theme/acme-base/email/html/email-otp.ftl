<#-- HTML part of the sign-in code mail. Variables: ${code}, ${ttlMinutes} (set in EmailOtpAuthenticator). -->
<#import "template.ftl" as layout>
<@layout.emailLayout>
${kcSanitize(msg("emailOtpBodyHtml", code, ttlMinutes))?no_esc}
</@layout.emailLayout>
