# bbledger on Oracle Cloud Always Free (ARM A1). Applied from CI — see
# .github/workflows/deploy.yml and infra/README.md for the required secrets.

terraform {
  required_providers {
    oci = { source = "oracle/oci" }
  }
  # OCI Object Storage, S3-compatible. bucket/region/endpoint arrive via
  # -backend-config at init time (generated from secrets in the workflow).
  backend "s3" {
    key                         = "bbledger/terraform.tfstate"
    skip_region_validation      = true
    skip_credentials_validation = true
    skip_requesting_account_id  = true
    skip_metadata_api_check     = true
    skip_s3_checksum            = true
    use_path_style              = true
  }
}

provider "oci" {
  tenancy_ocid = var.tenancy_ocid
  user_ocid    = var.user_ocid
  fingerprint  = var.fingerprint
  private_key  = var.private_key
  region       = var.region
}

variable "tenancy_ocid" { type = string }
variable "user_ocid" { type = string }
variable "fingerprint" { type = string }
variable "private_key" {
  type      = string
  sensitive = true
}
variable "region" { type = string }
variable "compartment_ocid" { type = string }
variable "ssh_public_key" {
  type    = string
  default = ""
}
variable "availability_domain_index" {
  type        = number
  default     = 0
  description = "Try 1 or 2 on 'Out of host capacity' errors"
}
variable "bot_token" {
  type      = string
  sensitive = true
}
variable "config_edn" {
  type        = string
  sensitive   = true
  description = "Full content of config.edn (see config.sample.edn)"
}
variable "ghcr_user" {
  type    = string
  default = "Schroedingberg"
}
variable "ghcr_token" {
  type        = string
  sensitive   = true
  description = "PAT with read:packages, for the VM to pull the private image"
}

data "oci_identity_availability_domains" "ads" {
  compartment_id = var.tenancy_ocid
}

data "oci_core_images" "ubuntu_arm" {
  compartment_id           = var.compartment_ocid
  operating_system         = "Canonical Ubuntu"
  operating_system_version = "24.04"
  shape                    = "VM.Standard.A1.Flex"
  sort_by                  = "TIMECREATED"
  sort_order               = "DESC"
}

resource "oci_core_vcn" "net" {
  compartment_id = var.compartment_ocid
  cidr_blocks    = ["10.0.0.0/24"]
  display_name   = "bbledger"
  dns_label      = "bbledger"
}

resource "oci_core_internet_gateway" "igw" {
  compartment_id = var.compartment_ocid
  vcn_id         = oci_core_vcn.net.id
  display_name   = "bbledger"
}

resource "oci_core_default_route_table" "rt" {
  manage_default_resource_id = oci_core_vcn.net.default_route_table_id
  route_rules {
    destination       = "0.0.0.0/0"
    network_entity_id = oci_core_internet_gateway.igw.id
  }
}

# The VCN's default security list already allows SSH in + all egress,
# which is all a long-polling bot needs (no inbound service ports).
resource "oci_core_subnet" "sub" {
  compartment_id = var.compartment_ocid
  vcn_id         = oci_core_vcn.net.id
  cidr_block     = "10.0.0.0/24"
  display_name   = "bbledger"
  dns_label      = "bot"
}

resource "oci_core_instance" "bot" {
  availability_domain = data.oci_identity_availability_domains.ads.availability_domains[var.availability_domain_index].name
  compartment_id      = var.compartment_ocid
  display_name        = "bbledger-bot"
  shape               = "VM.Standard.A1.Flex"

  shape_config {
    ocpus         = 1
    memory_in_gbs = 6
  }

  source_details {
    source_type = "image"
    source_id   = data.oci_core_images.ubuntu_arm.images[0].id
  }

  create_vnic_details {
    subnet_id        = oci_core_subnet.sub.id
    assign_public_ip = true
  }

  metadata = {
    ssh_authorized_keys = var.ssh_public_key
    user_data = base64encode(templatefile("${path.module}/cloud-init.yaml", {
      bot_token     = var.bot_token
      config_edn    = var.config_edn
      ghcr_user     = var.ghcr_user
      ghcr_token    = var.ghcr_token
      bot_unit      = file("${path.module}/../deploy/bbledger-bot.service")
      summary_unit  = file("${path.module}/../deploy/bbledger-summary.service")
      summary_timer = file("${path.module}/../deploy/bbledger-summary.timer")
    }))
  }

  # a newer Ubuntu image or edited cloud-init must not silently rebuild the
  # VM that holds the ledger data — recreate deliberately via taint/destroy
  lifecycle {
    ignore_changes = [source_details, metadata]
  }

  preserve_boot_volume = false
}

output "public_ip" {
  value = oci_core_instance.bot.public_ip
}
