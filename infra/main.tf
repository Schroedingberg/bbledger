# bbledger on a Hetzner Cloud CAX11 (ARM, ~3.79€/mo). Applied from CI — see
# .github/workflows/deploy.yml and infra/README.md for the required secrets.

terraform {
  required_providers {
    hcloud = { source = "hetznercloud/hcloud" }
  }
  # No backend block here: stateless mode (default) uses throwaway local
  # state; with STATE_* secrets set, the workflow generates a backend.tf
  # pointing at an S3-compatible bucket before init.
}

provider "hcloud" {
  token = var.hcloud_token
}

variable "hcloud_token" {
  type      = string
  sensitive = true
}
variable "server_type" {
  type        = string
  default     = "cax11"
  description = "cax11 = ARM 2vCPU/4GB; the CI image is multi-arch, so x86 types work too"
}
variable "location" {
  type    = string
  default = "fsn1"
}
variable "bot_token" {
  type      = string
  sensitive = true
}
variable "data_repo" {
  type        = string
  description = "SSH url of the private data repo holding household.ledger + config.edn"
}
variable "data_deploy_key" {
  type        = string
  sensitive   = true
  description = "SSH private key whose public half is a write deploy key on the data repo"
}
variable "ghcr_user" {
  type    = string
  default = "Schroedingberg"
}
variable "ghcr_token" {
  type        = string
  sensitive   = true
  default     = ""
  description = "PAT with read:packages — only needed while the GHCR package is private"
}

# every SSH key already uploaded to the (dedicated) project gets installed —
# manage keys in the Hetzner console, not here
data "hcloud_ssh_keys" "project" {}

# long-polling needs no inbound service ports; expose sshd only
resource "hcloud_firewall" "ssh_only" {
  name = "bbledger-ssh-only"
  rule {
    direction  = "in"
    protocol   = "tcp"
    port       = "22"
    source_ips = ["0.0.0.0/0", "::/0"]
  }
}

resource "hcloud_server" "bot" {
  name         = "bbledger-bot"
  server_type  = var.server_type
  image        = "ubuntu-24.04"
  location     = var.location
  ssh_keys     = data.hcloud_ssh_keys.project.ssh_keys[*].name
  firewall_ids = [hcloud_firewall.ssh_only.id]

  user_data = templatefile("${path.module}/cloud-init.yaml", {
    bot_token       = var.bot_token
    data_repo       = var.data_repo
    data_deploy_key = var.data_deploy_key
    ghcr_user       = var.ghcr_user
    ghcr_token      = var.ghcr_token
    bot_unit        = file("${path.module}/../deploy/bbledger-bot.service")
    summary_unit    = file("${path.module}/../deploy/bbledger-summary.service")
    summary_timer   = file("${path.module}/../deploy/bbledger-summary.timer")
    push_unit       = file("${path.module}/../deploy/bbledger-push.service")
    push_path       = file("${path.module}/../deploy/bbledger-push.path")
  })

  # an edited cloud-init must not silently rebuild a running VM — recreate
  # deliberately via taint/destroy (state lives in the data repo anyway)
  lifecycle {
    ignore_changes = [user_data]
  }
}

output "public_ip" {
  value = hcloud_server.bot.ipv4_address
}
