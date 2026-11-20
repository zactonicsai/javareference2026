{{- define "lp.name" -}}{{ .Release.Name }}{{- end -}}
{{- define "lp.labels" -}}
app.kubernetes.io/name: launchpad
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: aks-sso-platform
app.kubernetes.io/version: {{ .Values.image.tag | quote }}
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version }}
{{- end -}}
{{- define "lp.selector" -}}
app: {{ include "lp.name" . }}
{{- end -}}
{{- define "lp.https" -}}{{ hasPrefix "https://" .Values.publicUrl }}{{- end -}}
{{- define "lp.callback" -}}{{ .Values.publicUrl }}/login/oauth2/code/keycloak{{- end -}}
{{- define "lp.issuer" -}}{{ .Values.keycloak.publicUrl }}/realms/{{ .Values.keycloak.realm }}{{- end -}}
{{- define "lp.discovery" -}}{{ .Values.keycloak.internalUrl }}/realms/{{ .Values.keycloak.realm }}/.well-known/openid-configuration{{- end -}}
