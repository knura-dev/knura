{{- define "knura.fullname" -}}
{{- if contains .Chart.Name .Release.Name -}}
{{- .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name .Chart.Name | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}

{{- define "knura.labels" -}}
app.kubernetes.io/name: {{ .Chart.Name }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version }}
{{- end -}}

{{- define "knura.selectorLabels" -}}
app.kubernetes.io/name: {{ .Chart.Name }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{/* A properties value. A line break would let a value add settings of its own, so refuse it. */}}
{{- define "knura.prop" -}}
{{- $v := toString . -}}
{{- if or (contains "\n" $v) (contains "\r" $v) -}}
{{- fail (printf "a setting can't contain a line break: %q" $v) -}}
{{- end -}}
{{- $v -}}
{{- end -}}

{{/* Names of the knots whose actions include the Matter connector. */}}
{{- define "knura.matterKnots" -}}
{{- $names := list -}}
{{- range .Values.knots -}}
{{- if has $.Values.matter.id .actions -}}
{{- $names = append $names .name -}}
{{- end -}}
{{- end -}}
{{- join "," $names -}}
{{- end -}}

{{/* Checks the knots and connectors, so mistakes show at helm install, not as a crashing pod. */}}
{{- define "knura.validate" -}}
{{- if not .Values.existingSecret -}}
{{- fail "existingSecret: name the Secret with master.key (see deploy/README.md)" -}}
{{- end -}}
{{- $ids := keys .Values.webhooks -}}
{{- if .Values.matter.enabled -}}
{{- if hasKey .Values.webhooks .Values.matter.id -}}
{{- fail (printf "matter.id %s is also a webhook id; pick another" .Values.matter.id) -}}
{{- end -}}
{{- $ids = append $ids .Values.matter.id -}}
{{- end -}}
{{- range .Values.knots -}}
{{- if not (regexMatch "^[0-9A-Fa-f]{14}$" (toString .uid)) -}}
{{- fail (printf "knots: uid %v is not a tag UID (14 hex digits, like 042D6B02936980)" .uid) -}}
{{- end -}}
{{- if not .name -}}
{{- fail (printf "knots: give knot %s a name" .uid) -}}
{{- end -}}
{{- if not .actions -}}
{{- fail (printf "knots: knot %s has no actions" .name) -}}
{{- end -}}
{{- range .actions -}}
{{- if not (has . $ids) -}}
{{- fail (printf "knots: action %s is not in webhooks%s" . (ternary " or matter.id" "" $.Values.matter.enabled)) -}}
{{- end -}}
{{- end -}}
{{- end -}}
{{- if and .Values.matter.enabled (not (include "knura.matterKnots" .)) -}}
{{- fail (printf "matter.enabled: add %s to the actions of at least one knot" .Values.matter.id) -}}
{{- end -}}
{{- if and .Values.resolver.owner (not .Values.publicUrl) -}}
{{- fail "resolver.owner needs publicUrl: the address the resolver sends taps to" -}}
{{- end -}}
{{- end -}}

{{- define "knura.containerSecurity" -}}
allowPrivilegeEscalation: false
readOnlyRootFilesystem: true
capabilities:
  drop: [ALL]
{{- end -}}
