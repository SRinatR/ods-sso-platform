#!/usr/bin/env bash
set -euo pipefail

deploy_path="${1:-/opt/ods-platform}"

install -d -m 755 /etc/apt/apt.conf.d
cat > /etc/apt/apt.conf.d/80ods-network <<'EOF'
Acquire::ForceIPv4 "true";
Acquire::Retries "5";
Acquire::http::Timeout "30";
Acquire::https::Timeout "30";
DPkg::Lock::Timeout "120";
EOF

sed -i \
  -e 's|http://ru-msk1\.clouds\.archive\.ubuntu\.com/ubuntu/|https://ru-msk1.clouds.archive.ubuntu.com/ubuntu/|g' \
  -e 's|http://security\.ubuntu\.com/ubuntu|https://ru-msk1.clouds.archive.ubuntu.com/ubuntu|g' \
  -e 's|http://archive\.ubuntu\.com/ubuntu|https://ru-msk1.clouds.archive.ubuntu.com/ubuntu|g' \
  /etc/apt/sources.list /etc/apt/sources.list.d/*.sources 2>/dev/null || true

if ! dpkg-query -W -f='${Status}' openssh-server 2>/dev/null | grep -q 'ok installed'; then
  apt-get update
  DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends openssh-server
fi
systemctl unmask ssh
systemctl enable --now ssh

if [ -s /root/.ssh/authorized_keys ]; then
  install -d -m 755 /etc/ssh/sshd_config.d
  cat > /etc/ssh/sshd_config.d/99-ods-hardening.conf <<'EOF'
PasswordAuthentication no
KbdInteractiveAuthentication no
PermitRootLogin prohibit-password
MaxAuthTries 3
LoginGraceTime 30
EOF
  sshd -t
  systemctl reload ssh
fi

if ! command -v curl >/dev/null 2>&1; then
  apt-get update
  apt-get install -y --reinstall --no-install-recommends ca-certificates curl
fi

if ! command -v docker >/dev/null 2>&1; then
  apt-get update
  DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends docker.io docker-compose-v2
  systemctl enable --now docker
fi

if command -v iptables >/dev/null 2>&1 &&
  ! iptables -C FORWARD -j DOCKER-FORWARD >/dev/null 2>&1; then
  systemctl restart docker
fi

if ! command -v git >/dev/null 2>&1; then
  apt-get update
  apt-get install -y --no-install-recommends git
fi

if [ "$(free -m | awk '/^Mem:/{print $2}')" -lt 3000 ] && [ "$(swapon --show | wc -l)" -eq 0 ]; then
  fallocate -l 2G /swapfile
  chmod 600 /swapfile
  mkswap /swapfile
  swapon /swapfile
  echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi

mkdir -p "$deploy_path"
install -d -m 700 /etc/ods-platform
if [ ! -f /etc/ods-platform/production.env ]; then
  echo "GitHub Environment production will create /etc/ods-platform/production.env on the first deploy"
fi

if command -v ufw >/dev/null 2>&1; then
  ufw allow 22/tcp
  ufw allow 80/tcp
  ufw allow 443/tcp
fi

echo "Server bootstrap completed"
