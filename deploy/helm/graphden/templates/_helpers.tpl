{{/* Chart name, overridable. */}}
{{- define "graphden.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/* Fully-qualified release name. */}}
{{- define "graphden.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- $name := default .Chart.Name .Values.nameOverride -}}
{{- if contains $name .Release.Name -}}
{{- .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{/* Headless Service name — provides the stable per-pod DNS + SRV records. */}}
{{- define "graphden.headlessName" -}}
{{- printf "%s-headless" (include "graphden.fullname" .) | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/* Secret name — an existing one, or the chart-created default. */}}
{{- define "graphden.secretName" -}}
{{- if .Values.secrets.existingSecret -}}
{{- .Values.secrets.existingSecret -}}
{{- else -}}
{{- include "graphden.fullname" . -}}
{{- end -}}
{{- end -}}

{{/* Common labels. */}}
{{- define "graphden.labels" -}}
app.kubernetes.io/name: {{ include "graphden.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" }}
{{- end -}}

{{/* Selector labels. */}}
{{- define "graphden.selectorLabels" -}}
app.kubernetes.io/name: {{ include "graphden.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{/* Image ref, defaulting the tag to appVersion. */}}
{{- define "graphden.image" -}}
{{- $tag := default .Chart.AppVersion .Values.image.tag -}}
{{- printf "%s:%s" .Values.image.repository $tag -}}
{{- end -}}
