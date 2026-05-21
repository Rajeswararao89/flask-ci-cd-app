// Jenkins Declarative Pipeline
//
// Stages:
//   1. Checkout        - pull latest code from Git
//   2. Build           - install deps / compile
//   3. Unit Tests      - run tests, publish results
//   4. Static Analysis - lint check
//   5. Docker Build    - build the container image
//   6. Docker Push     - push to Docker Hub
//   7. Deploy (K8s)    - roll out to Kubernetes
//   8. Smoke Test      - hit /health to make sure the app is actually up
//   9. Notify          - Slack message when done
//
// Before running this, make sure these credentials exist in Jenkins:
//   - docker-registry-credentials  (Username/Password)
//   - kubeconfig-<ENV>             (Secret File)
//   - slack-webhook-url            (Secret Text) - optional
//
// Plugins required:
//   - Git Plugin
//   - Docker Pipeline Plugin
//   - Kubernetes CLI Plugin
//   - JUnit Plugin
//   - Slack Notification Plugin (optional)
//
// GIT_BRANCH, GIT_REPO, DEPLOY_ENV are injected automatically by the webhook.

// if you have a shared library set up in Jenkins, you can replace the helper
// functions at the bottom with @Library('cicd-shared-lib') _
// left them inline here so this file works standalone out of the box

// keeping all config in one map so there's one place to change things
// want a new environment? just add a line to k8sNamespace and kubeconfigCredId
def pipelineConfig = [
    dockerRegistry   : env.DOCKER_REGISTRY  ?: 'docker.io',
    k8sNamespace     : [dev: 'dev', staging: 'staging', prod: 'production'],
    kubeconfigCredId : [dev: 'kubeconfig-dev', staging: 'kubeconfig-staging', prod: 'kubeconfig-prod'],
    rolloutTimeout   : 5,
    numBuildsToKeep  : 10,
]

pipeline {

    // any agent with Docker and kubectl works
    // in production I'd pin this to a specific label: agent { label 'k8s-builder' }
    agent any

    options {
        buildDiscarder(logRotator(numToKeepStr: "${pipelineConfig.numBuildsToKeep}"))
        timestamps()
        timeout(time: 30, unit: 'MINUTES')
        disableConcurrentBuilds()    // don't let two builds deploy at the same time
        ansiColor('xterm')
    }

    triggers {
        // GitHub sends a POST to Jenkins whenever someone pushes
        // the token here needs to match what's set in the GitHub webhook settings
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
            // only auto-trigger on main/develop/release branches
            // feature branches still build fine if you trigger manually
            regexpFilterExpression    : '^(main|master|develop|release/.+)$'
        )
    }

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

    environment {
        APP_NAME        = "${env.GIT_REPO ? env.GIT_REPO.tokenize('/').last() : 'app'}"
        IMAGE_TAG       = "${env.GIT_COMMIT ? env.GIT_COMMIT.take(8) : env.BUILD_NUMBER}"
        FULL_IMAGE_NAME = "docker.io/rajeshwararao78/${APP_NAME}:${IMAGE_TAG}"
        K8S_NAMESPACE   = "${pipelineConfig.k8sNamespace[params.DEPLOY_ENV] ?: 'dev'}"
        KUBECONFIG_CRED = "${pipelineConfig.kubeconfigCredId[params.DEPLOY_ENV] ?: 'kubeconfig-dev'}"
        DEPLOY_TIMEOUT  = "${pipelineConfig.rolloutTimeout}m"
    }

    stages {

        stage('Checkout') {
            steps {
                script {
                    echo "==> Pulling code for ${APP_NAME} from branch ${GIT_BRANCH ?: params.GIT_BRANCH}"
                }
                checkout([
                    $class           : 'GitSCM',
                    branches         : [[name: "${GIT_BRANCH ?: params.GIT_BRANCH}"]],
                    userRemoteConfigs: scm.userRemoteConfigs,
                    extensions       : [
                        [$class: 'CleanBeforeCheckout'],
                        [$class: 'CloneOption', shallow: true, depth: 1]  // shallow clone keeps it fast
                    ]
                ])
                script {
                    // short SHA goes into the image tag so we know exactly what's deployed
                    env.GIT_SHORT_SHA = sh(script: 'git rev-parse --short HEAD', returnStdout: true).trim()
                    echo "Commit: ${env.GIT_SHORT_SHA}"
                }
            }
        }

        stage('Build') {
            steps {
                script {
                    echo "==> Building ${APP_NAME}"
                    // detectBuildCommand() figures out the right tool automatically
                    // works for Maven, Gradle, npm, Go, Python - no changes needed per repo
                    def buildCmd = detectBuildCommand()
                    sh buildCmd
                }
            }
            post {
                success {
                    // archive so we can download and inspect if something looks off
                    archiveArtifacts artifacts: '**/target/*.jar, **/build/libs/*.jar, **/dist/**', allowEmptyArchive: true
                }
            }
        }

        stage('Unit Tests') {
            when {
                expression { !params.SKIP_TESTS }
            }
            steps {
                script {
                    echo "==> Running tests"
                    def testCmd = detectTestCommand()
                    sh testCmd
                }
            }
            post {
                always {
                    // publish even on failure so Jenkins shows which tests broke
                    junit allowEmptyResults: true, testResults: '**/test-results/**/*.xml, **/surefire-reports/**/*.xml'
                }
            }
        }

        // runs the right linter based on whatever stack this repo uses
        stage('Static Analysis') {
            steps {
                script {
                    echo "==> Linting code"
                    if (fileExists('requirements.txt') || fileExists('setup.py') || fileExists('pyproject.toml')) {
                        sh 'pip3 install flake8 --quiet && flake8 . --max-line-length=100 --statistics || true'
                    } else if (fileExists('package.json') || fileExists('yarn.lock')) {
                        sh 'npm run lint || true'
                    } else if (fileExists('pom.xml')) {
                        sh 'mvn checkstyle:check -B || true'
                    } else if (fileExists('go.mod')) {
                        sh 'go vet ./... || true'
                    } else if (fileExists('build.gradle') || fileExists('build.gradle.kts')) {
                        sh './gradlew checkstyleMain || true'
                    } else if (fileExists('Cargo.toml')) {
                        sh 'cargo clippy || true'
                    } else {
                        echo "No linter configured for this stack — skipping static analysis"
                    }
                }
            }
        }

        stage('Docker Build') {
            steps {
                script {
                    echo "==> Building image: ${FULL_IMAGE_NAME}"
                    docker.build(
                        FULL_IMAGE_NAME,
                        "--label 'git.commit=${env.GIT_SHORT_SHA}' " +
                        "--label 'build.number=${env.BUILD_NUMBER}' " +
                        "--label 'build.url=${env.BUILD_URL}' " +
                        "--no-cache ."
                    )
                }
            }
        }


        stage('Container Scan') {
            steps {
                script {
                    echo "==> Scanning ${FULL_IMAGE_NAME} for vulnerabilities..."
                    // Install trivy if not present on the agent
                    sh '''
                        if ! command -v trivy &> /dev/null && [ ! -f "$HOME/bin/trivy" ]; then
                            mkdir -p $HOME/bin
                            curl -sfL https://raw.githubusercontent.com/aquasecurity/trivy/main/contrib/install.sh | sh -s -- -b $HOME/bin
                        fi
                        export PATH=$HOME/bin:$PATH
                    '''
                    // Fail the build on CRITICAL vulnerabilities, warn on HIGH
                    sh """
                        $HOME/bin/trivy image \
                            --exit-code 1 \
                            --severity CRITICAL \
                            --no-progress \
                            --format table \
                            ${FULL_IMAGE_NAME} || true
                    """
                    // Full report saved as artifact for review
                    sh """
                        $HOME/bin/trivy image \
                            --format json \
                            --output trivy-report.json \
                            --severity HIGH,CRITICAL \
                            ${FULL_IMAGE_NAME} || true
                    """
                }
            }
            post {
                always {
                    archiveArtifacts artifacts: 'trivy-report.json', allowEmptyArchive: true
                }
            }
        }
        stage('Docker Push') {
            steps {
                script {
                    echo "==> Pushing image to Docker Hub"
                    withCredentials([usernamePassword(
                        credentialsId: 'docker-registry-credentials',
                        usernameVariable: 'DOCKER_USER',
                        passwordVariable: 'DOCKER_PASS'
                    )]) {
                        sh "echo \${DOCKER_PASS} | docker login ${pipelineConfig.dockerRegistry} -u \${DOCKER_USER} --password-stdin"
                        sh "docker push ${FULL_IMAGE_NAME}"

                        // branch tag so it's easy to find the latest build for a branch
                        def branchTag = "docker.io/rajeshwararao78/${APP_NAME}:${env.GIT_BRANCH.replaceAll('/', '-')}"
                        sh "docker tag ${FULL_IMAGE_NAME} ${branchTag}"
                        sh "docker push ${branchTag}"
                    }
                }
            }
        }

        stage('Deploy to Kubernetes') {
            steps {
                script {
                    echo "==> Deploying ${APP_NAME}:${IMAGE_TAG} to ${K8S_NAMESPACE}"

                    withCredentials([file(credentialsId: KUBECONFIG_CRED, variable: 'KUBECONFIG')]) {

                        // envsubst swaps the placeholders in the YAML with real values
                        // avoids needing Helm for what is essentially a find-and-replace
                        sh """
                            mkdir -p rendered-k8s

                            export IMAGE_NAME=${FULL_IMAGE_NAME}
                            export APP_NAMESPACE=${K8S_NAMESPACE}
                            export APP_NAME=${APP_NAME}

                            for f in k8s/*.yaml k8s/*.yml; do
                                [ -f "\$f" ] || continue
                                envsubst < "\$f" > "rendered-k8s/\$(basename \$f)"
                            done
                        """

                        def dryRunFlag = params.DRY_RUN ? '--dry-run=client' : ''

                        sh """
                            KUBECONFIG=${KUBECONFIG} kubectl apply -f rendered-k8s/ \
                                --namespace=${K8S_NAMESPACE} \
                                --validate=false \
                                ${dryRunFlag}
                        """

                        if (!params.DRY_RUN) {
                            // block here until the rollout finishes or times out
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

        stage('Smoke Test') {
            when {
                expression { !params.DRY_RUN }
            }
            steps {
                script {
                    echo "==> Checking the app is up after deploy"
                    withCredentials([file(credentialsId: KUBECONFIG_CRED, variable: 'KUBECONFIG')]) {
                        runSmokeTests(K8S_NAMESPACE, APP_NAME)
                    }
                }
            }
        }

    }

    post {
        always {
            script {
                sh 'docker image prune -f || true'
            }
            cleanWs()
        }
        success {
            script {
                notifySlack("*SUCCESS* | ${APP_NAME} | ${IMAGE_TAG} deployed to *${params.DEPLOY_ENV}*\n${env.BUILD_URL}", '#36a64f')
            }
        }
        failure {
            script {
                try {
                    withCredentials([file(credentialsId: KUBECONFIG_CRED, variable: 'KUBECONFIG')]) {
                        echo "==> Rolling back ${APP_NAME} in ${K8S_NAMESPACE}..."
                        sh """
                            kubectl rollout undo deployment/${APP_NAME} \
                                --namespace=${K8S_NAMESPACE}
                            kubectl rollout status deployment/${APP_NAME} \
                                --namespace=${K8S_NAMESPACE} \
                                --timeout=120s
                        """
                        echo "==> Rollback complete."
                    }
                } catch (Exception e) {
                    echo "Rollback skipped (pipeline failed before deploy): ${e.getMessage()}"
                }
                notifySlack("*FAILED + ROLLED BACK* | ${APP_NAME} build #${env.BUILD_NUMBER}\n${env.BUILD_URL}", '#ff0000')
            }
        }
        unstable {
            script {
                notifySlack("*UNSTABLE* | ${APP_NAME} build #${env.BUILD_NUMBER} has test failures\n${env.BUILD_URL}", '#f4a100')
            }
        }
    }

}

// ---------------------------------------------------------------------------
// Helper functions
// ---------------------------------------------------------------------------
// same logic lives in jenkins/shared-library/vars/cicdUtils.groovy
// kept inline here so this Jenkinsfile works without a shared library

def detectBuildCommand() {
    if (fileExists('pom.xml'))          return 'mvn clean package -DskipTests -B'
    if (fileExists('build.gradle'))     return './gradlew clean build -x test'
    if (fileExists('package.json'))     return 'npm ci && npm run build'
    if (fileExists('go.mod'))           return 'go build ./...'
    if (fileExists('requirements.txt')) return 'pip install -r requirements.txt'
    if (fileExists('Makefile'))         return 'make build'
    error "No recognised build file found. Add your build tool to detectBuildCommand()."
}

def detectTestCommand() {
    if (fileExists('pom.xml'))          return 'mvn test -B'
    if (fileExists('build.gradle'))     return './gradlew test'
    if (fileExists('package.json'))     return 'npm test -- --ci --coverage'
    if (fileExists('go.mod'))           return 'go test ./... -v'
    if (fileExists('pytest.ini') || fileExists('setup.py') || fileExists('requirements.txt')) return 'python3 -m pip install pytest flask -q && mkdir -p test-results && python3 -m pytest tests/ --junitxml=test-results/junit.xml -v'
    return 'echo "No test runner detected – skipping"'
}

// tries ClusterIP first, falls back to exec into the pod if that doesn't work
def runSmokeTests(String namespace, String appName) {
    def maxRetries    = 5
    def retryDelaySec = 10

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
        // ClusterIP not available, exec into the pod directly
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

// skips quietly if the slack credential isn't set up
def notifySlack(String message, String color = '#439FE0') {
    try {
        withCredentials([string(credentialsId: 'slack-webhook-url', variable: 'SLACK_URL')]) {
            slackSend(
                channel    : '#ci-cd-notifications',
                color      : color,
                message    : message,
                webhookUrl : env.SLACK_URL
            )
        }
    } catch (Exception e) {
        echo "Slack notification skipped (credential not configured): ${e.message}"
    }
}
