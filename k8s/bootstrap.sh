#!/usr/bin/env bash
# bootstrap.sh — one-time cluster setup for tokenization-api on Docker Desktop Kubernetes
# Idempotent: safe to re-run; all creates use --dry-run=client | kubectl apply.
#
# Required env vars before running:
#   REGISTRY_USER       — local registry username
#   REGISTRY_PASS       — local registry password
#   AWS_ACCESS_KEY_ID   — AWS access key with kms:GenerateDataKey + kms:Decrypt
#   AWS_SECRET_ACCESS_KEY
#
# Usage:
#   export REGISTRY_USER=admin REGISTRY_PASS=... AWS_ACCESS_KEY_ID=... AWS_SECRET_ACCESS_KEY=...
#   chmod +x k8s/bootstrap.sh && ./k8s/bootstrap.sh

set -euo pipefail

: "${REGISTRY_USER:?REGISTRY_USER must be set}"
: "${REGISTRY_PASS:?REGISTRY_PASS must be set}"
: "${AWS_ACCESS_KEY_ID:?AWS_ACCESS_KEY_ID must be set}"
: "${AWS_SECRET_ACCESS_KEY:?AWS_SECRET_ACCESS_KEY must be set}"

NAMESPACE="tokenization"
INGRESS_NGINX_VERSION="v1.12.0"
INGRESS_NGINX_URL="https://raw.githubusercontent.com/kubernetes/ingress-nginx/controller-${INGRESS_NGINX_VERSION}/deploy/static/provider/cloud/deploy.yaml"

echo "==> [1/7] Installing nginx ingress controller (${INGRESS_NGINX_VERSION})..."
kubectl apply -f "${INGRESS_NGINX_URL}"

echo "==> [2/7] Waiting for ingress-nginx controller pod to be ready (up to 180s)..."
kubectl wait --namespace ingress-nginx \
  --for=condition=ready pod \
  --selector=app.kubernetes.io/component=controller \
  --timeout=180s

echo "==> [3/7] Creating namespace '${NAMESPACE}'..."
kubectl create namespace "${NAMESPACE}" \
  --dry-run=client -o yaml | kubectl apply -f -

echo "==> [4/7] Creating registry imagePullSecret 'registry-secret'..."
kubectl create secret docker-registry registry-secret \
  --namespace="${NAMESPACE}" \
  --docker-server="host.docker.internal:5001" \
  --docker-username="${REGISTRY_USER}" \
  --docker-password="${REGISTRY_PASS}" \
  --dry-run=client -o yaml | kubectl apply -f -

echo "==> [5/7] Creating AWS credentials secret 'aws-credentials'..."
AWS_CREDS_FILE="$(mktemp)"
cat > "${AWS_CREDS_FILE}" <<EOF
[default]
aws_access_key_id = ${AWS_ACCESS_KEY_ID}
aws_secret_access_key = ${AWS_SECRET_ACCESS_KEY}
EOF
kubectl create secret generic aws-credentials \
  --namespace="${NAMESPACE}" \
  --from-file=credentials="${AWS_CREDS_FILE}" \
  --dry-run=client -o yaml | kubectl apply -f -
rm -f "${AWS_CREDS_FILE}"

echo "==> [6/7] Patching ingress-nginx-controller service to LoadBalancer..."
kubectl patch svc ingress-nginx-controller \
  --namespace ingress-nginx \
  --type='json' \
  -p='[{"op":"replace","path":"/spec/type","value":"LoadBalancer"}]'

echo "==> [7/7] Done."
echo ""
echo "IMPORTANT — Add to your hosts file (run Notepad as Administrator):"
echo "  File: C:\\Windows\\System32\\drivers\\etc\\hosts"
echo "  Line: 127.0.0.1  tokenization-api.local"
echo ""
echo "Verify cluster state:"
echo "  kubectl get pods -n ingress-nginx"
echo "  kubectl get ns ${NAMESPACE}"
echo "  kubectl get secrets -n ${NAMESPACE}"
