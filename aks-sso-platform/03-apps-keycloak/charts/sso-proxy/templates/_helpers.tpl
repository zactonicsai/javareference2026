{{- define "sp.name" -}}{{ .Release.Name }}{{- end -}}
{{- define "sp.labels" -}}
app.kubernetes.io/name: sso-proxy
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: aks-sso-platform
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version }}
{{- end -}}
{{- define "sp.selector" -}}
app: {{ include "sp.name" . }}-sso
{{- end -}}
{{- define "sp.https" -}}{{ hasPrefix "https://" .Values.publicUrl }}{{- end -}}
{{- define "sp.clientId" -}}{{ default .Release.Name .Values.client.id }}{{- end -}}
{{- define "sp.cookieName" -}}{{ default (printf "_sso_%s" .Release.Name) .Values.oauth2Proxy.cookieName }}{{- end -}}
{{- define "sp.path" -}}{{ required "path is required (e.g. /project2/)" .Values.path }}{{- end -}}
{{- define "sp.pathNoSlash" -}}{{ trimSuffix "/" (include "sp.path" .) }}{{- end -}}
{{- define "sp.proxyPrefix" -}}{{ include "sp.pathNoSlash" . }}/oauth2{{- end -}}
{{- define "sp.callback" -}}{{ .Values.publicUrl }}{{ include "sp.proxyPrefix" . }}/callback{{- end -}}
{{- define "sp.publicIssuer" -}}{{ .Values.keycloak.publicUrl }}/realms/{{ .Values.keycloak.realm }}{{- end -}}
{{- define "sp.internalIssuer" -}}{{ .Values.keycloak.internalUrl }}/realms/{{ .Values.keycloak.realm }}{{- end -}}
{{- define "sp.host" -}}{{ (urlParse .Values.publicUrl).host }}{{- end -}}
