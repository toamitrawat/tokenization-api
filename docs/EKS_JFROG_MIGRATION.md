# Migration Guide: Docker Desktop K8s → AWS EKS + JFrog

This document outlines every change required to migrate the tokenization-api from the current
Docker Desktop Kubernetes + local registry setup to **AWS EKS** with **JFrog Artifactory** as the
container registry.

---

## Current State vs Target State

| Concern | Current (Docker Desktop) | Target (AWS EKS + JFrog) |
|---|---|---|
| Kubernetes cluster | Docker Desktop single-node | AWS EKS (managed control plane) |
| Container registry | `host.docker.internal:5001` (htpasswd) | JFrog Artifactory (Docker registry) |
| Database | Oracle XE on Docker (`host.docker.internal:1521`) | Amazon RDS for Oracle / Oracle on EC2 |
| AWS KMS auth | Static credentials file mounted as volume | IAM Roles for Service Accounts (IRSA) |
| Secrets management | Helm `--set` / k8s Secrets created by `bootstrap.sh` | AWS Secrets Manager + External Secrets Operator |
| Ingress | nginx-ingress on localhost | AWS ALB Ingress Controller (or nginx on NLB) |
| CI/CD | Jenkins in Docker container | Jenkins on EC2 / EKS, or migrate to CodePipeline/GitHub Actions |
| TLS | None (HTTP only, `insecure-skip-tls-verify`) | ACM certificates + TLS termination at ALB |
| DNS | `/etc/hosts` entry (`tokenization-api.local`) | Route 53 record pointing to ALB |
| Networking | Docker bridge (`kafka-net`) | VPC, private subnets, security groups |

---

## 1. AWS Infrastructure (Pre-requisites)

These resources must exist before the first deployment.

### 1.1 EKS Cluster

```bash
# Using eksctl (simplest path)
eksctl create cluster \
  --name tokenization-cluster \
  --region ap-south-1 \
  --version 1.29 \
  --nodegroup-name workers \
  --node-type t3.medium \
  --nodes 2 \
  --nodes-min 1 \
  --nodes-max 4 \
  --managed
```

Alternatively, use Terraform with the `terraform-aws-modules/eks/aws` module for repeatability.

### 1.2 VPC & Networking

- EKS nodes in **private subnets** (NAT gateway for outbound)
- Oracle DB in the same VPC (or VPC-peered if on separate account)
- Security group rules:
  - EKS nodes → Oracle DB port `1521`
  - EKS nodes → KMS endpoint (use VPC endpoint for `com.amazonaws.ap-south-1.kms`)
  - ALB → EKS nodes on port `8088`

### 1.3 Oracle Database

| Option | Notes |
|---|---|
| Amazon RDS for Oracle | Managed, Multi-AZ, automated backups. Change JDBC URL to RDS endpoint. |
| Oracle on EC2 | Self-managed. Same JDBC URL pattern, just point to the EC2 private IP. |
| Existing Oracle (on-prem/other VPC) | VPN or VPC peering + transit gateway required. |

**Action**: Update `datasource.url` in Helm values to the new Oracle endpoint.

### 1.4 KMS VPC Endpoint (Recommended)

Create a VPC Interface Endpoint for KMS to keep traffic off the public internet:

```bash
aws ec2 create-vpc-endpoint \
  --vpc-id vpc-xxxx \
  --service-name com.amazonaws.ap-south-1.kms \
  --vpc-endpoint-type Interface \
  --subnet-ids subnet-xxxx subnet-yyyy \
  --security-group-ids sg-xxxx
```

---

## 2. Container Registry — JFrog Artifactory

### 2.1 Create a Docker Repository in JFrog

- Log in to your JFrog instance (e.g., `https://yourcompany.jfrog.io`)
- Create a **local Docker repository** (e.g., `tokenization-docker-local`)
- Note the registry URL: `yourcompany.jfrog.io/tokenization-docker-local`

### 2.2 Files to Change

**`Jenkinsfile`** — replace registry references:

```groovy
environment {
    // OLD:
    // REGISTRY = 'host.docker.internal:5001'
    // NEW:
    REGISTRY      = 'yourcompany.jfrog.io/tokenization-docker-local'
    IMAGE_NAME    = 'tokenization-api'
    // ...
}
```

Docker login stage:

```groovy
stage('Docker Login') {
    steps {
        withCredentials([
            usernamePassword(credentialsId: 'jfrog-registry',
                             usernameVariable: 'REG_USER',
                             passwordVariable: 'REG_PASS')
        ]) {
            sh 'echo "$REG_PASS" | docker login yourcompany.jfrog.io -u "$REG_USER" --password-stdin'
        }
    }
}
```

**`helm/tokenization-api/values.yaml`** — update image repository:

```yaml
image:
  repository: yourcompany.jfrog.io/tokenization-docker-local/tokenization-api
  pullPolicy: IfNotPresent
  tag: "latest"
```

**`k8s/bootstrap.sh`** — update `imagePullSecret` to point to JFrog:

```bash
kubectl create secret docker-registry registry-secret \
  --namespace="${NAMESPACE}" \
  --docker-server="yourcompany.jfrog.io" \
  --docker-username="${REGISTRY_USER}" \
  --docker-password="${REGISTRY_PASS}" \
  --dry-run=client -o yaml | kubectl apply -f -
```

### 2.3 Helm Chart as OCI Artifact (Optional)

You can also push the Helm chart to JFrog:

```bash
helm package helm/tokenization-api
helm push tokenization-api-0.1.0.tgz oci://yourcompany.jfrog.io/tokenization-helm-local
```

Then in the pipeline:

```groovy
sh "helm upgrade --install ${HELM_RELEASE} oci://yourcompany.jfrog.io/tokenization-helm-local/tokenization-api --version 0.1.0 ..."
```

---

## 3. AWS Authentication — IRSA (IAM Roles for Service Accounts)

This is the **most important change**. The current setup mounts a static AWS credentials file
into the pod. On EKS, use IRSA instead — no static keys, automatic credential rotation.

### 3.1 Create an IAM Policy

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "kms:GenerateDataKey",
        "kms:Decrypt"
      ],
      "Resource": "arn:aws:kms:ap-south-1:538143631035:key/a7c5a1f1-ce1a-4348-acbe-5c150201cb9b"
    }
  ]
}
```

```bash
aws iam create-policy \
  --policy-name TokenizationKmsPolicy \
  --policy-document file://kms-policy.json
```

### 3.2 Associate OIDC Provider with EKS

```bash
eksctl utils associate-iam-oidc-provider \
  --cluster tokenization-cluster \
  --region ap-south-1 \
  --approve
```

### 3.3 Create IAM Role Linked to K8s ServiceAccount

```bash
eksctl create iamserviceaccount \
  --name tokenization-api \
  --namespace tokenization \
  --cluster tokenization-cluster \
  --region ap-south-1 \
  --attach-policy-arn arn:aws:iam::538143631035:policy/TokenizationKmsPolicy \
  --approve
```

This creates a K8s ServiceAccount annotated with the IAM role ARN.

### 3.4 Files to Change

**`helm/tokenization-api/values.yaml`**:

```yaml
serviceAccount:
  create: true   # set to false if eksctl already created it
  annotations:
    eks.amazonaws.com/role-arn: "arn:aws:iam::538143631035:role/TokenizationKmsRole"
  name: "tokenization-api"
```

**`helm/tokenization-api/templates/deployment.yaml`** — remove the AWS credentials volume mount:

```yaml
# DELETE these sections:
volumes:
  - name: aws-credentials
    secret:
      secretName: {{ .Values.existingSecrets.awsCredentials }}

volumeMounts:
  - name: aws-credentials
    mountPath: /root/.aws
    readOnly: true
```

**`helm/tokenization-api/templates/configmap.yaml`** — remove the credentials file env var:

```yaml
# DELETE this line:
AWS_SHARED_CREDENTIALS_FILE: "/root/.aws/credentials"
```

**`k8s/bootstrap.sh`** — remove the `aws-credentials` secret creation step (step 5/7).

**`helm/tokenization-api/values.yaml`** — remove:

```yaml
# DELETE:
existingSecrets:
  awsCredentials: aws-credentials
```

The AWS SDK will automatically discover credentials via the IRSA-injected web identity token
(projected into the pod by EKS at `/var/run/secrets/eks.amazonaws.com/serviceaccount/token`).

**`src/.../config/AwsKmsConfig.java`** — remove the `ProfileCredentialsProvider` block entirely.
On EKS with IRSA, the default credential chain handles everything:

```java
@Bean
public KmsClient kmsClient() {
    return KmsClient.builder()
            .region(Region.of(props.region()))
            .overrideConfiguration(cfg -> cfg
                    .apiCallTimeout(Duration.ofMillis(props.kms().apiTimeoutMs()))
                    .apiCallAttemptTimeout(Duration.ofMillis(props.kms().connectTimeoutMs()))
                    .retryPolicy(RetryPolicy.builder().numRetries(3).build()))
            .build();  // default credential chain picks up IRSA automatically
}
```

Remove `aws.profile` from `application.yml` and `AwsKmsProperties.java` — no longer needed.

---

## 4. Secrets Management

### Option A: AWS Secrets Manager + External Secrets Operator (Recommended)

Install the External Secrets Operator on EKS:

```bash
helm repo add external-secrets https://charts.external-secrets.io
helm install external-secrets external-secrets/external-secrets \
  --namespace external-secrets --create-namespace
```

Create a `SecretStore` pointing to AWS Secrets Manager:

```yaml
apiVersion: external-secrets.io/v1beta1
kind: SecretStore
metadata:
  name: aws-secret-store
  namespace: tokenization
spec:
  provider:
    aws:
      service: SecretsManager
      region: ap-south-1
      auth:
        jwt:
          serviceAccountRef:
            name: tokenization-api   # uses IRSA
```

Create an `ExternalSecret` that syncs secrets from Secrets Manager into k8s:

```yaml
apiVersion: external-secrets.io/v1beta1
kind: ExternalSecret
metadata:
  name: tokenization-secret
  namespace: tokenization
spec:
  refreshInterval: 1h
  secretStoreRef:
    name: aws-secret-store
    kind: SecretStore
  target:
    name: tokenization-secret   # k8s Secret name (matches existingSecrets.appSecret)
  data:
    - secretKey: SPRING_DATASOURCE_USERNAME
      remoteRef:
        key: tokenization-api/db
        property: username
    - secretKey: SPRING_DATASOURCE_PASSWORD
      remoteRef:
        key: tokenization-api/db
        property: password
    - secretKey: TOKENIZATION_HMAC_KEY_BASE64
      remoteRef:
        key: tokenization-api/hmac
        property: keyBase64
```

**Files to change**: Remove `helm/tokenization-api/templates/secret.yaml` — secrets are now
managed externally. Update `values.yaml` to remove `secrets: {}` and the Helm `--set secrets.*`
flags from `Jenkinsfile`.

### Option B: Keep Helm-Managed Secrets (Simpler, Less Secure)

No template changes needed. Continue passing `--set secrets.dbPassword=...` at deploy time
from Jenkins credentials. This is acceptable for non-production environments.

---

## 5. Ingress — AWS ALB Ingress Controller

### 5.1 Install AWS Load Balancer Controller

```bash
helm repo add eks https://aws.github.io/eks-charts
helm install aws-load-balancer-controller eks/aws-load-balancer-controller \
  --namespace kube-system \
  --set clusterName=tokenization-cluster \
  --set serviceAccount.create=false \
  --set serviceAccount.name=aws-load-balancer-controller
```

(Requires its own IRSA role with the [recommended IAM policy](https://docs.aws.amazon.com/eks/latest/userguide/lbc-helm.html).)

### 5.2 Update Ingress Template

**`helm/tokenization-api/templates/ingress.yaml`** — add ALB annotations:

```yaml
spec:
  ingressClassName: alb     # was: nginx
  rules:
    - host: tokenization-api.yourdomain.com
      # ...
```

**`helm/tokenization-api/values.yaml`**:

```yaml
ingress:
  enabled: true
  className: alb
  host: tokenization-api.yourdomain.com
  path: /
  pathType: Prefix
  annotations:
    alb.ingress.kubernetes.io/scheme: internet-facing   # or "internal"
    alb.ingress.kubernetes.io/target-type: ip
    alb.ingress.kubernetes.io/listen-ports: '[{"HTTPS": 443}]'
    alb.ingress.kubernetes.io/certificate-arn: "arn:aws:acm:ap-south-1:538143631035:certificate/xxxx"
    alb.ingress.kubernetes.io/healthcheck-path: /actuator/health/readiness
    alb.ingress.kubernetes.io/healthcheck-port: "8088"
```

### 5.3 DNS (Route 53)

Create a CNAME or alias record:

```
tokenization-api.yourdomain.com → <ALB DNS name from `kubectl get ingress`>
```

### 5.4 TLS

- Request a certificate in **AWS Certificate Manager (ACM)** for `tokenization-api.yourdomain.com`
- Reference the ACM ARN in the ingress annotation (`alb.ingress.kubernetes.io/certificate-arn`)
- TLS terminates at the ALB; traffic to pods stays HTTP on port 8088

---

## 6. CI/CD Pipeline — Jenkinsfile Changes

### 6.1 Full Diff Summary

| Section | Current | Target |
|---|---|---|
| `REGISTRY` | `host.docker.internal:5001` | `yourcompany.jfrog.io/tokenization-docker-local` |
| `KUBECONFIG` | `/root/.kube/config` (Docker Desktop) | EKS kubeconfig via `aws eks update-kubeconfig` |
| Docker Login | `docker login host.docker.internal:5001` | `docker login yourcompany.jfrog.io` |
| Credentials | `registry-username`/`registry-password` (string) | `jfrog-registry` (usernamePassword) |
| Deploy `--set` | `secrets.dbUsername`, `secrets.dbPassword`, `secrets.hmacKeyBase64` | Remove if using External Secrets, keep if Helm-managed |

### 6.2 Updated Jenkinsfile Skeleton

```groovy
pipeline {
    agent any

    environment {
        REGISTRY      = 'yourcompany.jfrog.io/tokenization-docker-local'
        IMAGE_NAME    = 'tokenization-api'
        IMAGE_TAG     = "${BUILD_NUMBER}"
        HELM_RELEASE  = 'tokenization-api'
        NAMESPACE     = 'tokenization'
        HELM_CHART    = 'helm/tokenization-api'
        AWS_REGION    = 'ap-south-1'
        EKS_CLUSTER   = 'tokenization-cluster'
    }

    stages {
        stage('Checkout') {
            steps { checkout scm }
        }

        stage('Build & Test') {
            steps { sh './mvnw clean verify --batch-mode' }
            post {
                always {
                    junit testResults: '**/target/surefire-reports/*.xml',
                          allowEmptyResults: true
                }
            }
        }

        stage('Docker Login') {
            steps {
                withCredentials([
                    usernamePassword(credentialsId: 'jfrog-registry',
                                     usernameVariable: 'REG_USER',
                                     passwordVariable: 'REG_PASS')
                ]) {
                    sh 'echo "$REG_PASS" | docker login yourcompany.jfrog.io -u "$REG_USER" --password-stdin'
                }
            }
        }

        stage('Docker Build & Push') {
            steps {
                sh """
                    docker build -t ${REGISTRY}/${IMAGE_NAME}:${IMAGE_TAG} .
                    docker push ${REGISTRY}/${IMAGE_NAME}:${IMAGE_TAG}
                """
            }
        }

        stage('Configure kubectl') {
            steps {
                sh "aws eks update-kubeconfig --name ${EKS_CLUSTER} --region ${AWS_REGION}"
            }
        }

        stage('Deploy') {
            steps {
                // If using External Secrets, no --set secrets.* needed
                sh """
                    helm upgrade --install ${HELM_RELEASE} ${HELM_CHART} \
                      --namespace ${NAMESPACE} \
                      --create-namespace \
                      --set image.tag=${IMAGE_TAG} \
                      --atomic --wait --timeout 180s
                """
            }
        }

        stage('Verify') {
            steps {
                sh """
                    kubectl rollout status deployment/${HELM_RELEASE} \
                      -n ${NAMESPACE} --timeout=120s
                """
            }
        }
    }
}
```

### 6.3 Jenkins Agent Requirements

If Jenkins runs on EC2 or EKS:

- **AWS CLI v2** installed (for `aws eks update-kubeconfig`)
- **IAM role** attached to Jenkins EC2 instance / IRSA for Jenkins pod, with permissions:
  - `eks:DescribeCluster` (to fetch kubeconfig)
  - (Optional) Secrets Manager read access if Jenkins injects secrets
- **Docker** available (Docker-in-Docker sidecar, or kaniko for rootless builds)
- **helm** and **kubectl** installed

---

## 7. bootstrap.sh — EKS Version

Replace the Docker Desktop bootstrap with an EKS-oriented script. Key differences:

| Step | Docker Desktop | EKS |
|---|---|---|
| Ingress controller | nginx from raw YAML | AWS LB Controller via Helm |
| imagePullSecret | `host.docker.internal:5001` | `yourcompany.jfrog.io` |
| AWS credentials secret | Static credentials file | **Remove** — use IRSA |
| Ingress service patch | `LoadBalancer` | Not needed (ALB controller handles it) |

---

## 8. Database Connectivity

### 8.1 Update JDBC URL

**`helm/tokenization-api/values.yaml`**:

```yaml
datasource:
  # OLD:
  # url: "jdbc:oracle:thin:@//host.docker.internal:1521/XEPDB1"
  # NEW (RDS example):
  url: "jdbc:oracle:thin:@//tokenization-db.xxxx.ap-south-1.rds.amazonaws.com:1521/ORCL"
```

### 8.2 Security Group Rules

Ensure the EKS node security group is allowed inbound on port `1521` in the Oracle DB's
security group.

### 8.3 Connection Pooling

For production, add HikariCP settings to `application.yml`:

```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: ${HIKARI_MAX_POOL_SIZE:10}
      minimum-idle: ${HIKARI_MIN_IDLE:5}
      connection-timeout: 30000
      idle-timeout: 600000
      max-lifetime: 1800000
```

---

## 9. Production Hardening (EKS-Specific)

### 9.1 Pod Disruption Budget

Create `helm/tokenization-api/templates/pdb.yaml`:

```yaml
apiVersion: policy/v1
kind: PodDisruptionBudget
metadata:
  name: {{ include "tokenization-api.fullname" . }}
  namespace: {{ .Release.Namespace }}
spec:
  minAvailable: 1
  selector:
    matchLabels:
      {{- include "tokenization-api.selectorLabels" . | nindent 6 }}
```

### 9.2 Horizontal Pod Autoscaler

```yaml
# Add to values.yaml
autoscaling:
  enabled: true
  minReplicas: 2
  maxReplicas: 6
  targetCPUUtilizationPercentage: 70
```

Create `helm/tokenization-api/templates/hpa.yaml`:

```yaml
{{- if .Values.autoscaling.enabled }}
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: {{ include "tokenization-api.fullname" . }}
  namespace: {{ .Release.Namespace }}
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: {{ include "tokenization-api.fullname" . }}
  minReplicas: {{ .Values.autoscaling.minReplicas }}
  maxReplicas: {{ .Values.autoscaling.maxReplicas }}
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: {{ .Values.autoscaling.targetCPUUtilizationPercentage }}
{{- end }}
```

### 9.3 Resource Requests (Right-Sizing)

Increase from Docker Desktop defaults for production:

```yaml
resources:
  requests:
    cpu: 500m
    memory: 1Gi
  limits:
    cpu: "2"
    memory: 2Gi

javaOpts: >-
  -XX:+UseG1GC
  -XX:MaxGCPauseMillis=200
  -Xms1g
  -Xmx1536m
  -XX:+HeapDumpOnOutOfMemoryError
  -XX:HeapDumpPath=/var/log/heapdumps
```

### 9.4 Pod Anti-Affinity

Spread pods across AZs — add to `deployment.yaml` under `spec.template.spec`:

```yaml
affinity:
  podAntiAffinity:
    preferredDuringSchedulingIgnoredDuringExecution:
      - weight: 100
        podAffinityTerm:
          labelSelector:
            matchLabels:
              {{- include "tokenization-api.selectorLabels" . | nindent 14 }}
          topologyKey: topology.kubernetes.io/zone
```

### 9.5 Network Policy

Restrict pod traffic to only what's needed:

```yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: tokenization-api
  namespace: tokenization
spec:
  podSelector:
    matchLabels:
      app.kubernetes.io/name: tokenization-api
  policyTypes: [Ingress, Egress]
  ingress:
    - from:
        - namespaceSelector:
            matchLabels:
              name: ingress-nginx    # or ALB controller namespace
      ports:
        - port: 8088
  egress:
    - to: []    # Oracle DB, KMS endpoint — restrict by CIDR or namespace
      ports:
        - port: 1521
        - port: 443    # KMS HTTPS
```

---

## 10. Observability (Recommended)

| Concern | Tool | Notes |
|---|---|---|
| Metrics | Prometheus + Grafana | Expose `/actuator/prometheus` (add `micrometer-registry-prometheus` dependency) |
| Logs | Fluent Bit → CloudWatch / OpenSearch | DaemonSet on EKS nodes; structured JSON logs already in place |
| Traces | AWS X-Ray or OpenTelemetry | Add OTEL Java agent as `JAVA_OPTS` javaagent |
| Alerts | CloudWatch Alarms or Grafana alerts | Circuit breaker open, error rate > threshold, pod restarts |

---

## 11. Migration Checklist

Use this as a step-by-step execution order:

- [ ] **Infra**: Create VPC, subnets, NAT gateway, security groups
- [ ] **Infra**: Create EKS cluster (`eksctl` or Terraform)
- [ ] **Infra**: Create/configure Oracle DB (RDS or EC2) in same VPC
- [ ] **Infra**: Create KMS VPC endpoint
- [ ] **IAM**: Create KMS IAM policy
- [ ] **IAM**: Associate OIDC provider with EKS
- [ ] **IAM**: Create IRSA role for `tokenization-api` ServiceAccount
- [ ] **JFrog**: Create Docker repository in JFrog
- [ ] **JFrog**: Add JFrog credentials to Jenkins (`jfrog-registry`)
- [ ] **Secrets**: Store secrets in AWS Secrets Manager
- [ ] **Secrets**: Install External Secrets Operator on EKS
- [ ] **Secrets**: Create SecretStore + ExternalSecret manifests
- [ ] **Helm**: Update `values.yaml` — image repo, datasource URL, ingress, IRSA annotation
- [ ] **Helm**: Remove `aws-credentials` volume mount from `deployment.yaml`
- [ ] **Helm**: Remove `AWS_SHARED_CREDENTIALS_FILE` from `configmap.yaml`
- [ ] **Helm**: Remove `secret.yaml` (if using External Secrets)
- [ ] **Helm**: Add PDB, HPA, NetworkPolicy templates
- [ ] **Code**: Remove `ProfileCredentialsProvider` logic from `AwsKmsConfig.java`
- [ ] **Code**: Remove `aws.profile` from `application.yml` and `AwsKmsProperties.java`
- [ ] **Pipeline**: Update `Jenkinsfile` — registry URL, docker login, kubeconfig via `aws eks`
- [ ] **Pipeline**: Install AWS CLI, kubectl, helm on Jenkins agent
- [ ] **Ingress**: Install AWS LB Controller on EKS
- [ ] **DNS**: Create Route 53 record for the ALB
- [ ] **TLS**: Request ACM certificate, add ARN to ingress annotations
- [ ] **Test**: Deploy to EKS, verify health probes, tokenize/detokenize flow
- [ ] **Test**: Verify circuit breaker triggers on KMS timeout (block KMS endpoint temporarily)
- [ ] **Cleanup**: Decommission Docker Desktop k8s setup and local registry

---

## 12. Files Changed Summary

| File | Action | What Changes |
|---|---|---|
| `Jenkinsfile` | Modify | Registry URL, docker login, add `aws eks update-kubeconfig` stage |
| `helm/tokenization-api/values.yaml` | Modify | Image repo, datasource URL, ingress class/host/annotations, IRSA, autoscaling, resources |
| `helm/tokenization-api/templates/deployment.yaml` | Modify | Remove `aws-credentials` volume + mount, add pod anti-affinity |
| `helm/tokenization-api/templates/configmap.yaml` | Modify | Remove `AWS_SHARED_CREDENTIALS_FILE` |
| `helm/tokenization-api/templates/secret.yaml` | Delete | Replaced by External Secrets (or keep if Helm-managed) |
| `helm/tokenization-api/templates/ingress.yaml` | Modify | ALB annotations, TLS |
| `helm/tokenization-api/templates/pdb.yaml` | Create | Pod Disruption Budget |
| `helm/tokenization-api/templates/hpa.yaml` | Create | Horizontal Pod Autoscaler |
| `helm/tokenization-api/templates/networkpolicy.yaml` | Create | Restrict pod traffic |
| `k8s/bootstrap.sh` | Rewrite | EKS-oriented: ALB controller, JFrog secret, no AWS creds secret |
| `k8s/jenkins-setup.sh` | Delete/Rewrite | Not needed if Jenkins on EC2; rewrite if Jenkins on EKS |
| `src/.../config/AwsKmsConfig.java` | Modify | Remove `ProfileCredentialsProvider` block |
| `src/.../config/AwsKmsProperties.java` | Modify | Remove `profile` field |
| `src/main/resources/application.yml` | Modify | Remove `aws.profile`, add HikariCP settings |
| `.env.example` | Modify | Update with EKS-relevant env vars |
