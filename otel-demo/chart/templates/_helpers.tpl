{{/*
Image reference. Registry comes from global.imageRegistry, then the image's own registry.
*/}}
{{- define "otel-poc.image" -}}
{{- $reg := .root.Values.global.imageRegistry | default .image.registry -}}
{{- if $reg }}{{ $reg }}/{{ end }}{{ .image.repository }}:{{ .image.tag }}
{{- end }}

{{/*
Multus annotation. Rendered only when enabled, and only with an IP in values.
*/}}
{{- define "otel-poc.networks" -}}
{{- if .enabled }}
{{- $nets := list }}
{{- range .networks }}
{{- $nets = append $nets (dict "name" (required "networkAttachments.networks[].name is required" .name) "namespace" (required "networkAttachments.networks[].namespace is required" .namespace) "ips" (required "networkAttachments.networks[].ips is required" .ips)) }}
{{- end }}
{{- if not $nets }}
{{- fail "networkAttachments.networks is required when networkAttachments.enabled is true" }}
{{- end }}
k8s.v1.cni.cncf.io/networks: {{ $nets | toJson | quote }}
{{- end }}
{{- end }}
