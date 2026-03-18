pipeline {
    agent any

    environment {
        REGISTRY      = 'host.docker.internal:5001'
        IMAGE_NAME    = 'tokenization-api'
        IMAGE_TAG     = "${BUILD_NUMBER}"
        HELM_RELEASE  = 'tokenization-api'
        NAMESPACE     = 'tokenization'
        HELM_CHART    = 'helm/tokenization-api'
        KUBECONFIG    = '/root/.kube/config'
    }

    options {
        buildDiscarder(logRotator(numToKeepStr: '10'))
        timestamps()
        timeout(time: 20, unit: 'MINUTES')
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
            }
        }

        stage('Build & Test') {
            steps {
                sh './mvnw clean verify --batch-mode'
            }
            post {
                always {
                    junit testResults: '**/target/surefire-reports/*.xml', allowEmptyResults: true
                }
            }
        }

        stage('Docker Login') {
            steps {
                withCredentials([
                    string(credentialsId: 'registry-username', variable: 'REG_USER'),
                    string(credentialsId: 'registry-password', variable: 'REG_PASS')
                ]) {
                    sh 'echo "$REG_PASS" | docker login host.docker.internal:5001 -u "$REG_USER" --password-stdin'
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

        stage('Deploy') {
            steps {
                withCredentials([
                    string(credentialsId: 'db-username',     variable: 'DB_USERNAME'),
                    string(credentialsId: 'db-password',     variable: 'DB_PASSWORD'),
                    string(credentialsId: 'hmac-key-base64', variable: 'HMAC_KEY')
                ]) {
                    script {
                        try {
                            sh """
                                helm upgrade --install ${HELM_RELEASE} ${HELM_CHART} \
                                  --namespace ${NAMESPACE} \
                                  --create-namespace \
                                  --set image.tag=${IMAGE_TAG} \
                                  --set secrets.dbUsername="${DB_USERNAME}" \
                                  --set secrets.dbPassword="${DB_PASSWORD}" \
                                  --set secrets.hmacKeyBase64="${HMAC_KEY}" \
                                  --atomic \
                                  --wait \
                                  --timeout 180s
                            """
                        } catch (err) {
                            // Capture diagnostics while pod still exists (before --atomic deletes it)
                            sh """
                                echo '=== Pod describe at failure ==='
                                kubectl describe pods -n ${NAMESPACE} \
                                  -l app.kubernetes.io/name=${IMAGE_NAME} || true
                                echo '=== Pod logs at failure ==='
                                kubectl logs -n ${NAMESPACE} \
                                  -l app.kubernetes.io/name=${IMAGE_NAME} \
                                  --tail=200 || true
                                echo '=== Pod events ==='
                                kubectl get events -n ${NAMESPACE} \
                                  --sort-by=.lastTimestamp --field-selector type=Warning || true
                            """
                            throw err
                        }
                    }
                }
            }
        }

        stage('Verify') {
            steps {
                sh """
                    kubectl rollout status deployment/${HELM_RELEASE} \
                      -n ${NAMESPACE} --timeout=120s
                    kubectl get pods -n ${NAMESPACE} -l app.kubernetes.io/name=${IMAGE_NAME}
                """
            }
        }
    }

    post {
        failure {
            sh """
                echo '=== Pod describe ==='
                kubectl describe pods -n ${NAMESPACE} \
                  -l app.kubernetes.io/name=${IMAGE_NAME} || true
                echo '=== Pod logs ==='
                kubectl logs -n ${NAMESPACE} \
                  -l app.kubernetes.io/name=${IMAGE_NAME} \
                  --tail=100 || true
                echo '=== Helm history ==='
                helm history ${HELM_RELEASE} -n ${NAMESPACE} || true
            """
        }
        always {
            cleanWs()
        }
    }
}
