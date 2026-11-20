{{- define "kc.labels" -}}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: aks-sso-platform
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version }}
{{- end -}}
{{- define "kc.url" -}}{{ .Values.publicUrl }}{{ .Values.relativePath }}{{- end -}}
{{- define "kc.internalUrl" -}}http://keycloak.{{ .Release.Namespace }}.svc.cluster.local:8080{{ .Values.relativePath }}{{- end -}}
{{- define "kc.issuer" -}}{{ include "kc.url" . }}/realms/{{ .Values.realm.name }}{{- end -}}
{{- define "kc.https" -}}{{ hasPrefix "https://" .Values.publicUrl }}{{- end -}}
{{- define "kc.gwNs" -}}{{ (split "/" .Values.gateway)._0 }}{{- end -}}
{{- define "kc.gwName" -}}{{ (split "/" .Values.gateway)._1 }}{{- end -}}
{{- define "kc.jdbc" -}}
{{- if .Values.postgres.enabled -}}jdbc:postgresql://keycloak-postgres.{{ .Release.Namespace }}.svc.cluster.local:5432/{{ .Values.postgres.database }}
{{- else -}}{{ required "postgres.externalUrl is required when postgres.enabled=false" .Values.postgres.externalUrl }}{{- end -}}
{{- end -}}
