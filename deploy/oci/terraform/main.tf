# OrbitWatch on OCI — single-VM deployment (the cheapest way to run the whole stack in the cloud).
#
# Creates: VCN + public subnet + internet gateway + security list, and one compute instance
# (Ampere A1 by default — Always Free eligible shape) that cloud-init turns into a Docker host,
# clones the repo and runs `docker compose up`. Kafka runs on the VM. For a managed broker, see
# ../README.md ("OCI Streaming") — the services only need KAFKA_* env vars changed.
#
# Usage:
#   terraform init && terraform apply -var-file=orbitwatch.tfvars
#   then open http://<public_ip>:8080

terraform {
  required_version = ">= 1.5"
  required_providers {
    oci = { source = "oracle/oci", version = ">= 6.0" }
  }
}

provider "oci" {
  tenancy_ocid     = var.tenancy_ocid
  user_ocid        = var.user_ocid
  fingerprint      = var.fingerprint
  private_key_path = var.private_key_path
  region           = var.region
}

# ---------------- network ----------------
resource "oci_core_vcn" "ow" {
  compartment_id = var.compartment_ocid
  cidr_blocks    = ["10.42.0.0/16"]
  display_name   = "orbitwatch-vcn"
  dns_label      = "orbitwatch"
}

resource "oci_core_internet_gateway" "ow" {
  compartment_id = var.compartment_ocid
  vcn_id         = oci_core_vcn.ow.id
  display_name   = "orbitwatch-igw"
}

resource "oci_core_route_table" "ow" {
  compartment_id = var.compartment_ocid
  vcn_id         = oci_core_vcn.ow.id
  display_name   = "orbitwatch-rt"
  route_rules {
    destination       = "0.0.0.0/0"
    destination_type  = "CIDR_BLOCK"
    network_entity_id = oci_core_internet_gateway.ow.id
  }
}

resource "oci_core_security_list" "ow" {
  compartment_id = var.compartment_ocid
  vcn_id         = oci_core_vcn.ow.id
  display_name   = "orbitwatch-sl"

  egress_security_rules {
    destination = "0.0.0.0/0"
    protocol    = "all"
  }
  # SSH from the operator only
  ingress_security_rules {
    protocol = "6"
    source   = var.admin_cidr
    tcp_options {
      min = 22
      max = 22
    }
  }
  # Dashboard / gateway API
  ingress_security_rules {
    protocol = "6"
    source   = var.dashboard_cidr
    tcp_options {
      min = 8080
      max = 8080
    }
  }
  # Kafka UI, Grafana — operator only
  ingress_security_rules {
    protocol = "6"
    source   = var.admin_cidr
    tcp_options {
      min = 8390
      max = 8390
    }
  }
  ingress_security_rules {
    protocol = "6"
    source   = var.admin_cidr
    tcp_options {
      min = 3000
      max = 3000
    }
  }
}

resource "oci_core_subnet" "ow" {
  compartment_id    = var.compartment_ocid
  vcn_id            = oci_core_vcn.ow.id
  cidr_block        = "10.42.1.0/24"
  display_name      = "orbitwatch-public"
  dns_label         = "pub"
  route_table_id    = oci_core_route_table.ow.id
  security_list_ids = [oci_core_security_list.ow.id]
}

# ---------------- compute ----------------
data "oci_identity_availability_domains" "ads" {
  compartment_id = var.tenancy_ocid
}

data "oci_core_images" "ol9" {
  compartment_id           = var.compartment_ocid
  operating_system         = "Oracle Linux"
  operating_system_version = "9"
  shape                    = var.shape
  sort_by                  = "TIMECREATED"
  sort_order               = "DESC"
}

resource "oci_core_instance" "ow" {
  compartment_id      = var.compartment_ocid
  availability_domain = data.oci_identity_availability_domains.ads.availability_domains[0].name
  display_name        = "orbitwatch"
  shape               = var.shape

  shape_config {
    ocpus         = var.ocpus
    memory_in_gbs = var.memory_gb
  }

  source_details {
    source_type = "image"
    source_id               = data.oci_core_images.ol9.images[0].id
    boot_volume_size_in_gbs = 80
  }

  create_vnic_details {
    subnet_id        = oci_core_subnet.ow.id
    assign_public_ip = true
    hostname_label   = "orbitwatch"
  }

  metadata = {
    ssh_authorized_keys = file(var.ssh_public_key_path)
    user_data = base64encode(templatefile("${path.module}/cloud-init.yaml", {
      repo_url    = var.repo_url
      repo_ref    = var.repo_ref
      offline     = var.offline_replay ? "1" : "0"
    }))
  }

  freeform_tags = { project = "orbitwatch", purpose = "demo" }
}

output "public_ip" { value = oci_core_instance.ow.public_ip }
output "dashboard_url" { value = "http://${oci_core_instance.ow.public_ip}:8080" }
output "ssh" { value = "ssh opc@${oci_core_instance.ow.public_ip}" }
