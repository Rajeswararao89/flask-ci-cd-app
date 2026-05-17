/**
 * jenkins/shared-library/vars/cicdUtils.groovy
 *
 * Jenkins Shared Library – utility functions that can be reused across
 * multiple Jenkinsfiles in your organisation.
 *
 * Setup:
 *   1. Create a Git repository called e.g. 'cicd-shared-lib'.
 *   2. Put this file at vars/cicdUtils.groovy inside that repo.
 *   3. In Jenkins → Manage Jenkins → Configure System → Global Pipeline Libraries,
 *      add the library pointing to your repo.
 *   4. In any Jenkinsfile: @Library('cicd-shared-lib') _
 *      Then call: cicdUtils.detectBuildCommand() etc.
 */

/**
 * Build-tool auto-detection.
 * Returns the shell command string needed to compile the project.
 */
def detectBuildCommand() {
    if (fileExists('pom.xml'))             return 'mvn clean package -DskipTests -B'
    if (fileExists('build.gradle'))        return './gradlew clean build -x test'
    if (fileExists('build.gradle.kts'))    return './gradlew clean build -x test'
    if (fileExists('package.json'))        return 'npm ci && npm run build'
    if (fileExists('yarn.lock'))           return 'yarn install --frozen-lockfile && yarn build'
    if (fileExists('go.mod'))              return 'go build ./...'
    if (fileExists('requirements.txt'))    return 'pip install --upgrade pip && pip install -r requirements.txt'
    if (fileExists('pyproject.toml'))      return 'pip install --upgrade pip && pip install .'
    if (fileExists('Cargo.toml'))          return 'cargo build --release'
    if (fileExists('Makefile'))            return 'make build'
    error "detectBuildCommand: no recognised build file found in workspace root."
}

/**
 * Test-runner auto-detection.
 */
def detectTestCommand() {
    if (fileExists('pom.xml'))             return 'mvn test -B'
    if (fileExists('build.gradle') || fileExists('build.gradle.kts'))
                                           return './gradlew test'
    if (fileExists('package.json'))        return 'npm test -- --ci --coverage'
    if (fileExists('yarn.lock'))           return 'yarn test --ci --coverage'
    if (fileExists('go.mod'))              return 'go test ./... -v'
    if (fileExists('pytest.ini') || fileExists('setup.cfg') || fileExists('pyproject.toml'))
                                           return 'pytest --junitxml=test-results/junit.xml -v'
    if (fileExists('Cargo.toml'))          return 'cargo test'
    return 'echo "No test runner detected – skipping unit tests"'
}

/**
 * Run static analysis / linting based on detected tech stack.
 * Supports Python, Node/Yarn, Maven, Go, Gradle, and Rust.
 * Safe to call on any repo — falls back gracefully if no linter is configured.
 */
def runStaticAnalysis() {
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

/**
 * Render Kubernetes YAML templates by substituting environment variables.
 *
 * @param srcDir   directory containing *.yaml / *.yml template files
 * @param destDir  directory where rendered files are written (default: /tmp/k8s-rendered)
 */
def renderK8sTemplates(String srcDir = 'k8s', String destDir = '/tmp/k8s-rendered') {
    sh """
        mkdir -p ${destDir}
        for f in ${srcDir}/*.yaml ${srcDir}/*.yml; do
            [ -f "\$f" ] || continue
            envsubst < "\$f" > "${destDir}/\$(basename \$f)"
            echo "Rendered: \$f → ${destDir}/\$(basename \$f)"
        done
    """
}

/**
 * Apply rendered Kubernetes manifests to the target cluster.
 *
 * @param renderedDir   directory of rendered YAML files
 * @param namespace     Kubernetes namespace
 * @param kubeconfigVar environment variable name holding the kubeconfig path
 * @param dryRun        if true, pass --dry-run=client (validate only)
 */
def applyManifests(String renderedDir, String namespace, String kubeconfigVar = 'KUBECONFIG', Boolean dryRun = false) {
    def dryRunFlag = dryRun ? '--dry-run=client' : ''
    sh """
        kubectl apply -f ${renderedDir}/ \
            --namespace=${namespace} \
            ${dryRunFlag}
    """
}

/**
 * Wait for a Deployment rollout to complete.
 *
 * @param deploymentName  name of the Kubernetes Deployment
 * @param namespace       target namespace
 * @param timeoutMinutes  how long to wait before failing the stage
 */
def waitForRollout(String deploymentName, String namespace, Integer timeoutMinutes = 5) {
    sh """
        kubectl rollout status deployment/${deploymentName} \
            --namespace=${namespace} \
            --timeout=${timeoutMinutes}m
    """
}

/**
 * Tag and push a Docker image to the configured registry.
 *
 * @param localImage   image name as built locally
 * @param registry     registry base URL
 * @param credId       Jenkins credential ID (Username/Password)
 */
def pushDockerImage(String localImage, String registry, String credId = 'docker-registry-credentials') {
    withCredentials([usernamePassword(credentialsId: credId, usernameVariable: 'REG_USER', passwordVariable: 'REG_PASS')]) {
        sh """
            echo \${REG_PASS} | docker login ${registry} -u \${REG_USER} --password-stdin
            docker push ${localImage}
        """
    }
}

/**
 * Send a Slack message.  Requires the Slack Notification Plugin.
 *
 * @param message  Slack mrkdwn message text
 * @param color    Hex color for the attachment bar
 * @param channel  Slack channel (default: #ci-cd-notifications)
 * @param credId   Jenkins credential holding the webhook URL
 */
def notifySlack(String message, String color = '#439FE0',
                String channel = '#ci-cd-notifications',
                String credId = 'slack-webhook-url') {
    try {
        withCredentials([string(credentialsId: credId, variable: 'SLACK_URL')]) {
            slackSend channel: channel, color: color, message: message, webhookUrl: env.SLACK_URL
        }
    } catch (Exception e) {
        echo "Slack notification failed (non-fatal): ${e.message}"
    }
}

/**
 * Determine the deployment environment from the branch name.
 * Convention:
 *   main / master  → prod
 *   release/*      → staging
 *   everything else → dev
 */
def envFromBranch(String branch) {
    if (branch ==~ /^(main|master)$/)       return 'prod'
    if (branch ==~ /^release\/.+$/)         return 'staging'
    return 'dev'
}
