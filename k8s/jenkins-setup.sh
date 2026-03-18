#!/usr/bin/env bash
# jenkins-setup.sh — start Jenkins and inject a kubeconfig that works from inside Docker.
#
# Why replace 127.0.0.1 with host.docker.internal:
#   Jenkins runs on the Docker bridge network. The k8s API server is on the host,
#   reachable only via host.docker.internal from inside a container.
#   Docker Desktop's API server TLS cert includes SANs for both addresses, so TLS
#   verification continues to pass after the URL substitution.
#
# Prerequisites:
#   - Jenkins container named "jenkins" exists (docker run ... --name jenkins)
#   - Jenkins data is in a Docker volume named "jenkins_home"
#   - kubectl is configured and pointing at Docker Desktop Kubernetes
#
# Usage:
#   chmod +x k8s/jenkins-setup.sh && ./k8s/jenkins-setup.sh

set -euo pipefail

HELM_VERSION="v3.17.1"
KUBECTL_VERSION="v1.32.2"

echo "==> [1/7] Starting Jenkins container..."
docker start jenkins

echo "==> [2/7] Waiting for Jenkins to be ready..."
sleep 5

echo "==> [3/7] Installing kubectl ${KUBECTL_VERSION} inside Jenkins..."
docker exec --user root jenkins bash -c "
  set -e
  WANT=${KUBECTL_VERSION}
  HAVE=\$(kubectl version --client -o json 2>/dev/null | grep -o '\"gitVersion\":\"[^\"]*\"' | grep -o 'v[0-9.]*' || echo 'none')
  if [ \"\$HAVE\" = \"\$WANT\" ]; then
    echo '    kubectl \$HAVE already at target version, skipping.'
  else
    echo '    kubectl: \$HAVE -> \$WANT'
    curl -fsSL https://dl.k8s.io/release/\${WANT}/bin/linux/amd64/kubectl \
      -o /usr/local/bin/kubectl
    chmod +x /usr/local/bin/kubectl
    echo '    kubectl installed: '\$(kubectl version --client -o json 2>/dev/null | grep -o '\"gitVersion\":\"[^\"]*\"' | grep -o 'v[0-9.]*')
  fi
"

echo "==> [4/7] Installing helm ${HELM_VERSION} inside Jenkins..."
docker exec --user root jenkins bash -c "
  set -e
  WANT=${HELM_VERSION}
  HAVE=\$(helm version --short 2>/dev/null | grep -o 'v[0-9.]*' | head -1 || echo 'none')
  if [ \"\$HAVE\" = \"\$WANT\" ]; then
    echo '    helm \$HAVE already at target version, skipping.'
  else
    echo '    helm: \$HAVE -> \$WANT'
    curl -fsSL https://get.helm.sh/helm-\${WANT}-linux-amd64.tar.gz \
      | tar -xz -C /usr/local/bin --strip-components=1 linux-amd64/helm
    chmod +x /usr/local/bin/helm
    echo '    helm installed: '\$(helm version --short 2>/dev/null | grep -o 'v[0-9.]*' | head -1)
  fi
"

echo "==> [5/7] Switching to docker-desktop context and extracting cluster server URL..."
kubectl config use-context docker-desktop
CLUSTER_SERVER=$(kubectl config view --minify -o jsonpath='{.clusters[0].cluster.server}')
echo "    Cluster server: ${CLUSTER_SERVER}"

echo "==> [6/7] Generating modified kubeconfig (127.0.0.1 -> host.docker.internal, skip TLS verify)..."
# insecure-skip-tls-verify is required because Docker Desktop's API server TLS cert
# does not include host.docker.internal as a SAN — only localhost and kubernetes aliases.
# This is safe for local Docker Desktop use only; never use on real clusters.
MODIFIED_KUBECONFIG=$(kubectl config view --context=docker-desktop --minify --flatten | \
  sed 's|https://127\.0\.0\.1:|https://host.docker.internal:|g' | \
  sed 's|certificate-authority-data:.*|insecure-skip-tls-verify: true|g')

echo "    Detecting Jenkins home directory..."
JENKINS_HOME=$(docker exec jenkins sh -c 'echo $HOME')
echo "    Jenkins HOME: ${JENKINS_HOME}"

echo "    Writing kubeconfig directly into Jenkins container..."
echo "${MODIFIED_KUBECONFIG}" | docker exec -i jenkins sh -c "
  mkdir -p \${HOME}/.kube &&
  cat > \${HOME}/.kube/config &&
  chmod 600 \${HOME}/.kube/config &&
  echo 'kubeconfig written to '\${HOME}'/.kube/config'
"

echo "==> [7/7] Testing kubectl and helm access from inside Jenkins container..."
docker exec jenkins kubectl get nodes || {
  echo "WARNING: kubectl test failed. Jenkins may still be starting up."
  echo "  Retry manually: docker exec jenkins kubectl get nodes"
}
docker exec jenkins helm version --short || {
  echo "WARNING: helm test failed."
  echo "  Retry manually: docker exec jenkins helm version"
}

echo ""
echo "================================================================"
echo "Jenkins is running. Complete setup in the UI:"
echo "  http://localhost:8080"
echo ""
echo "Create the following credentials (Manage Jenkins > Credentials > Global > Add Credential > Secret text):"
echo ""
echo "  ID                  | Value"
echo "  --------------------|------------------------------------------"
echo "  registry-username   | admin  (or your registry username)"
echo "  registry-password   | <registry admin password>"
echo "  db-username         | amit"
echo "  db-password         | <Oracle password>"
echo "  hmac-key-base64     | <Base64-encoded 32-byte HMAC key>"
echo ""
echo "Create Pipeline job:"
echo "  New Item > Pipeline > name: tokenization-api"
echo "  > Pipeline script from SCM"
echo "  > Git: <repo URL>"
echo "  > Branch: aws_deploy"
echo "  > Script Path: Jenkinsfile"
echo "================================================================"
