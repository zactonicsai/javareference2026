{{- define "gc.labels" -}}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: aks-sso-platform
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version }}
{{- end -}}
