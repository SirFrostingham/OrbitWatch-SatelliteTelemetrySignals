variable "tenancy_ocid" {
  type = string
}
variable "user_ocid" {
  type = string
}
variable "fingerprint" {
  type = string
}
variable "private_key_path" {
  type = string
}
variable "region" {
  type    = string
  default = "us-phoenix-1"
}
variable "compartment_ocid" {
  type = string
}

variable "ssh_public_key_path" {
  type = string
}

# Ampere A1 is Always-Free eligible up to 4 OCPU / 24 GB per tenancy.
variable "shape" {
  type    = string
  default = "VM.Standard.A1.Flex"
}
variable "ocpus" {
  type    = number
  default = 4
}
variable "memory_gb" {
  type    = number
  default = 24
}

variable "admin_cidr" {
  type        = string
  description = "CIDR allowed to reach SSH / Kafka UI / Grafana (your IP/32)"
}
variable "dashboard_cidr" {
  type        = string
  description = "CIDR allowed to reach the dashboard on 8080 (0.0.0.0/0 for a public demo)"
  default     = "0.0.0.0/0"
}

variable "repo_url" {
  type    = string
  default = "https://github.com/sirfrostingham/SatelliteTelemetrySignals.git"
}
variable "repo_ref" {
  type    = string
  default = "main"
}

variable "offline_replay" {
  type        = bool
  default     = false
  description = "true = replay the committed CelesTrak snapshot instead of polling the live feed"
}
