/**
 * CI/CD Pipeline – Jenkinsfile (Declarative)
 *
 * Stages:
 *   1. Checkout        – clone the triggering Git repo
 *   2. Build           – compile / package the application artifact
 *   3. Unit Tests      – run tests and publish JUnit results
 *   4. Static Analysis – run code-quality checks (optional)
 *   5. Docker Build    – build & tag the container image
 *   6. Docker Push     – push image to the configured registry
 *   7. Deploy (K8s)    – apply Kubernetes manifests via kubectl
 *   8. Smoke Test      – lightweight post-deploy health check
 *   9. Notify          – send Slack / email on success or failure
 *
 * Required Jenkins Credentials (configure in Manage Credentials):
 *   - docker-registry-credentials  (Username/Password)
 *   - kubeconfig-<ENV>             (Secret File) – one per target cluster
 *   - slack-webhook-url            (Secret Text, optional)
 *
 * Required Jenkins Plugins:
 *   - Git Plugin
 *   - Docker Pipeline Plugin
 *   - Kubernetes CLI Plugin  (kubectl)
 *   - JUnit Plugin
 *   - Slack Notification Plugin (optional)
 *
 * Pipeline Parameters (auto-populated by Generic Webhook Trigger Plugin):
 *   - DEPLOY_ENV  : target environment  (dev | staging | prod)
 *   - GIT_REPO    : repository full name (e.g. org/service-name)
 *   - GIT_BRANCH  : branch that triggered the build
 */

// ─── Shared library helpers (loaded from vars/) ───────────────────────────────
// If your Jenkins instance has a shared library configured, you can replace the
// inline helper closures below with @Library('cicd-shared-lib') import.

// ─── Global configuration map ─────────────────────────────────────────────────
def pipelineConfig = [
    // Docker registry base URL (override via job parameter if needed)
    dockerRegistry   : env.DOCKER_REGISTRY  ?: 'docker.io',
    // Kubernetes namespace per environment
    k8sNamespace     : [dev: 'dev', staging: 'staging', prod: 'production'],
    // Kubeconfig credential IDs per environment
    kubeconfigCredId : [dev: 'kubeconfig-dev', staging: 'kubeconfig-staging', prod: 'kubeconfig-prod'],
    // Max minutes to wait for a rollout to complete
    rolloutTimeout   : 5,
    // Number of old builds to keep
    numBuildsToKeep  : 10,
]

// ─── Pipeline definition ──────────────────────────────────────────────────────
pipeline {

    // Run on any agent that has Docker and kubectl available.
    // In a real cluster you would specify a label, e.g. agent { label 'k8s-builder' }
    agent any

    // ── Build retention ──────────────────────────────────────────────────────
    options {
        buildDiscarder(logRotator(numToKeepStr: "${pipelineConfig.numBuildsToKeep}"))
        timestamps()
        timeout(time: 30, unit: 'MINUTES')
        disableConcurrentBuilds()          // prevent race conditions on the same branch
        ansiColor('xterm')
    }

    // ── Triggers ─────────────────────────────────────────────────────────────
    triggers {
        // Generic Webhook Trigger Plugin – fires on any push to the watched repo.
        // Configure the matching token in the Git provider's webhook settings.
        GenericTrigger(
            genericVariables: [
                [key: 'GIT_BRANCH', value: '$.ref',        regexpFilter: 'refs/heads/'],
                [key: 'GIT_REPO',   value: '$.repository.full_name'],
                [key: 'GIT_COMMIT', value: '$.after']
            ],
            causeString      : 'Triggered by push to $GIT_REPO on branch $GIT_BRANCH',
            token            : env.WEBHOOK_TOKEN ?: 'CHANGE_ME',
            printContributedVariables: true,
            printPostContent          : false,
            silentResponse            : false,
            regexpFilterText          : '$GIT_BRANCH',
            // Only build main / develop / release branches automatically.
            // Feature branches can still be built manually.
            regexpFilterExpression    : '^(main|master|develop|release/.+)$'
        )
    }

    // ── Parameters (fallback for manual runs) ────────────────────────────────
    parameters {
        choice(
            name        : 'DEPLOY_ENV',
            choices     : ['dev', 'staging', 'prod'],
            description : 'Target Kubernetes environment'
        )
        string(
            name        : 'GIT_BRANCH',
            defaultValue: 'main',
            description : 'Branch to build (overridden by webhook trigger)'
        )
        booleanParam(
            name        : 'SKIP_TESTS',
            defaultValue: false,
            description : 'Skip unit tests (use only for hotfixes)'
        )
        booleanParam(
            name        : 'DRY_RUN',
            defaultValue: false,
            description : 'kubectl apply --dry-run=client (validate manifests without deploying)'
        )
    }

    // ── Environment variables available to all stages ────────────────────────
    environment {
        APP_NAME        = "${env.GIT_REPO ? env.GIT_REPO.tokenize('/').last() : 'app'}"
        IMAGE_TAG       = "${env.GIT_COMMIT ? env.GIT_COMMIT.take(8) : env.BUILD_NUMBER}"
        FULL_IMAGE_NAME = "docker.io/rajeshwararao78/${APP_NAME}:${IMAGE_TAG}"
        K8S_NAMESPACE   = "${pipelineConfig.k8sNamespace[params.DEPLOY_ENV] ?: 'dev'}"
        KUBECONFIG_CRED = "${pipelineConfig.kubeconfigCredId[params.DEPLOY_ENV] ?: 'kubeconfig-dev'}"
        DEPLOY_TIMEOUT  = "${pipelineConfig.rolloutTimeout}m"
    }

    // ─────────────────────────────────────────────────────────────────────────
    stages {

        // ── 1. Checkout ───────────────────────────────────────────────────────
        stage('Checkout') {
            steps {
                script {
                    echo "==> Checking out ${APP_NAME} @ ${GIT_BRANCH ?: params.GIT_BRANCH}"
                }
                checkout([
                    $class           : 'GitSCM',
                    branches         : [[name: "${GIT_BRANCH ?: params.GIT_BRANCH}"]],
                    userRemoteConfigs: scm.userRemoteConfigs,
                    extensions       : [
                        [$class: 'CleanBeforeCheckout'],          // ensure clean workspace
                        [$class: 'CloneOption', shallow: true, depth: 1]  // faster shallow clone
                    ]
                ])
                script {
                    // Expose short SHA for traceability in artifact names / image tags
                    env.GIT_SHORT_SHA = sh(script: 'git rev-parse --short HEAD', returnStdout: true).trim()
                    echo "Short SHA: ${env.GIT_SHORT_SHA}"
                }
            }
        }

        // ── 2. Build ──────────────────────────────────────────────────────────
        stage('Build') {
            steps {
                script {
                    echo "==> Building artifact for ${APP_NAME}"
                    // Detect build tool and run appropriate build command.
                    // Extend this map to support Go, Python, Node, etc.
                    def buildCmd = detectBuildCommand()
                    sh buildCmd
                }
            }
            post {
                success {
                    // Archive build artifacts so they are downloadable from the build page.
                    archiveArtifacts artifacts: '**/target/*.jar, **/build/libs/*.jar, **/dist/**', allowEmptyArchive: true
                }
            }
        }

        // ── 3. Unit Tests ─────────────────────────────────────────────────────
        stage('Unit Tests') {
            when {
                expression { !params.SKIP_TESTS }
            }
            steps {
                script {
                    echo "==> Running unit tests"
                    def testCmd = detectTestCommand()
                    sh testCmd
                }
            }
            post {
                always {
                    // Publish JUnit results regardless of pass/fail so failures are visible.
                    junit allowEmptyResults: true, testResults: '**/test-results/**/*.xml, **/surefire-reports/**/*.xml'
                }
            }
        }

        // ── 4. Static Analysis ─────────────────────────────────────
        stage('Static Analysis') {
            steps {
                script {
                    echo "==> Running static analysis with flake8"
                    sh """
                        . venv/bin/activate
                        pip install flake8 --quiet
                        echo "--- flake8 results ---"
                        flake8 app.py --max-line-length=100 --statistics || true
                        echo "--- Static analysis complete ---"
                    """
               }
            }
        }
        // ── 5. Docker Build ───────────────────────────────────────────────────
        stage('Docker Build') {
            steps {
                script {
                    echo "==> Building Docker image: ${FULL_IMAGE_NAME}"
                    docker.build(
                        FULL_IMAGE_NAME,
                        "--label 'git.commit=${env.GIT_SHORT_SHA}' " +
                        "--label 'build.number=${env.BUILD_NUMBER}' " +
                        "--label 'build.url=${env.BUILD_URL}' " +
                        "--no-cache ."           // --no-cache keeps images reproducible in CI
                    )
                }
            }
        }

        // ── 6. Docker Push ────────────────────────────────────────────────────
        stage('Docker Push') {
            steps {
                script {
                    echo "==> Pushing image to registry"
                    withCredentials([usernamePassword(
                        credentialsId: 'docker-registry-credentials',
                        usernameVariable: 'DOCKER_USER',
                        passwordVariable: 'DOCKER_PASS'
                    )]) {
                        sh "echo \${DOCKER_PASS} | docker login ${pipelineConfig.dockerRegistry} -u \${DOCKER_USER} --password-stdin"
                        sh "docker push ${FULL_IMAGE_NAME}"

                        // Also push a stable 'latest' / branch tag for convenience
                        def branchTag = "docker.io/rajeshwararao78/${APP_NAME}:${env.GIT_BRANCH.replaceAll('/', '-')}"
                        sh "docker tag ${FULL_IMAGE_NAME} ${branchTag}"
                        sh "docker push ${branchTag}"
                    }
                }
            }
        }

        // ── 7. Deploy to Kubernetes ───────────────────────────────────────────
        stage('Deploy to Kubernetes') {
            steps {
                script {
                    echo "==> Deploying ${APP_NAME}:${IMAGE_TAG} to ${K8S_NAMESPACE} (${params.DEPLOY_ENV})"

                    withCredentials([file(credentialsId: KUBECONFIG_CRED, variable: 'KUBECONFIG')]) {

                        // Render the Kubernetes manifests with the current image tag.
                        // Uses envsubst so no Helm dependency is required.
                        sh """
                            mkdir -p rendered-k8s
                            
                            export IMAGE_NAME=${FULL_IMAGE_NAME}
                            export APP_NAMESPACE=${K8S_NAMESPACE}
                            export APP_NAME=${APP_NAME}

                            # Substitute environment variables inside all YAML templates
                            for f in k8s/*.yaml k8s/*.yml; do
                                [ -f "\$f" ] || continue
                                envsubst < "\$f" > "rendered-k8s/\$(basename \$f)"
                            done
                        """

                        def dryRunFlag = params.DRY_RUN ? '--dry-run=client' : ''

                        sh """
                            KUBECONFIG=${KUBECONFIG} kubectl apply -f rendered-k8s/ \
                                --namespace=${K8S_NAMESPACE} \
                                ${dryRunFlag}
                        """

                        if (!params.DRY_RUN) {
                            // Wait for the rollout to complete within the allowed timeout.
                            sh """
                                KUBECONFIG=${KUBECONFIG} kubectl rollout status \
                                    deployment/${APP_NAME} \
                                    --namespace=${K8S_NAMESPACE} \
                                    --timeout=${DEPLOY_TIMEOUT}
                            """
                        }
                    }
                }
            }
        }

        // ── 8. Smoke Test ─────────────────────────────────────────────────────
        stage('Smoke Test') {
            when {
                expression { !params.DRY_RUN }
            }
            steps {
                script {
                    echo "==> Running post-deploy smoke tests"
                    withCredentials([file(credentialsId: KUBECONFIG_CRED, variable: 'KUBECONFIG')]) {
                        runSmokeTests(K8S_NAMESPACE, APP_NAME)
                    }
                }
            }
        }

    } // end stages

    // ─────────────────────────────────────────────────────────────────────────
    post {
        always {
            script {
                // Clean up dangling Docker images to save disk space on the agent.
                sh 'docker image prune -f || true'
            }
            cleanWs()
        }
        success {
            script {
                notifySlack("✅ *SUCCESS* | ${APP_NAME} | ${IMAGE_TAG} deployed to *${params.DEPLOY_ENV}*\n${env.BUILD_URL}", '#36a64f')
            }
        }
        failure {
            script {
                notifySlack("❌ *FAILURE* | ${APP_NAME} build #${env.BUILD_NUMBER} failed\n${env.BUILD_URL}", '#ff0000')
            }
        }
        unstable {
            script {
                notifySlack("⚠️ *UNSTABLE* | ${APP_NAME} build #${env.BUILD_NUMBER} has test failures\n${env.BUILD_URL}", '#f4a100')
            }
        }
    }

} // end pipeline

// =============================================================================
// Helper functions (Groovy closures)
// =============================================================================

 // This pipeline is intentionally tech-stack agnostic.
 // detectBuildCommand() auto-detects Maven, Gradle, npm, Go, or Python
 // based on files present in the repository root.
 // The Flask app in this repo demonstrates the Python path.
 
def detectBuildCommand() {
    if (fileExists('pom.xml'))          return 'mvn clean package -DskipTests -B'
    if (fileExists('build.gradle'))     return './gradlew clean build -x test'
    if (fileExists('package.json'))     return 'npm ci && npm run build'
    if (fileExists('go.mod'))           return 'go build ./...'
    if (fileExists('requirements.txt')) return 'pip install -r requirements.txt'
    if (fileExists('Makefile'))         return 'make build'
    error "No recognised build file found. Add your build tool to detectBuildCommand()."
}

/**
 * Detect which test tool the repository uses and return the test command.
 */
def detectTestCommand() {
    if (fileExists('pom.xml'))          return 'mvn test -B'
    if (fileExists('build.gradle'))     return './gradlew test'
    if (fileExists('package.json'))     return 'npm test -- --ci --coverage'
    if (fileExists('go.mod'))           return 'go test ./... -v'
    if (fileExists('pytest.ini') || fileExists('setup.py') || fileExists('requirements.txt')) return 'python3 -m pip install pytest flask -q && mkdir -p test-results && python3 -m pytest tests/ --junitxml=test-results/junit.xml -v'
    return 'echo "No test runner detected – skipping"'
}

/**
 * Run a lightweight health-check against the just-deployed service.
 * Tries the Kubernetes service ClusterIP first, then falls back to pod exec.
 *
 * @param namespace  K8s namespace
 * @param appName    Deployment / Service name
 */
def runSmokeTests(String namespace, String appName) {
    def maxRetries   = 5
    def retryDelaySec = 10

    // Retrieve the ClusterIP of the service (may not exist in all setups).
    def serviceIP = sh(
        script: "kubectl get svc ${appName} -n ${namespace} -o jsonpath='{.spec.clusterIP}' 2>/dev/null || echo ''",
        returnStdout: true
    ).trim()

    if (serviceIP) {
        retry(maxRetries) {
            sleep retryDelaySec
            def httpStatus = sh(
                script: "curl -s -o /dev/null -w '%{http_code}' http://${serviceIP}/healthz 2>/dev/null || echo '000'",
                returnStdout: true
            ).trim()
            if (httpStatus != '200') {
                error "Smoke test failed: /health returned HTTP ${httpStatus}"
            }
            echo "Smoke test passed (HTTP ${httpStatus})"
        }
    } else {
        // Fall back: exec into a running pod and curl localhost
        def pod = sh(
            script: "kubectl get pods -n ${namespace} -l app=${appName} -o jsonpath='{.items[0].metadata.name}' 2>/dev/null || echo ''",
            returnStdout: true
        ).trim()

        if (pod) {
            sh "kubectl exec -n ${namespace} ${pod} -- curl -sf http://localhost/healthz || (echo 'Health endpoint unreachable – check your /health route' && exit 1)"
        } else {
            echo "Warning: No running pods found for ${appName} in ${namespace}. Skipping smoke test."
        }
    }
}

/**
 * Send a Slack notification.
 * Requires the Slack Notification Plugin and a 'slack-webhook-url' secret text credential.
 *
 * @param message  Text to send (supports Slack mrkdwn)
 * @param color    Attachment colour hex string (e.g. '#36a64f')
 */
def notifySlack(String message, String color = '#439FE0') {
    withCredentials([string(credentialsId: 'slack-webhook-url', variable: 'SLACK_URL')]) {
        slackSend(
            channel    : '#ci-cd-notifications',
            color      : color,
            message    : message,
            webhookUrl : env.SLACK_URL
        )
    }
}
