// jenkins/shared-library/vars/cicdUtils.groovy
//
// Shared library with helper functions used across multiple Jenkinsfiles.
// Keeps the main Jenkinsfile clean and avoids copy-pasting the same logic
// into every repo.
//
// How to set this up:
//   1. Create a repo called 'cicd-shared-lib' (or whatever name you prefer)
//   2. Put this file at vars/cicdUtils.groovy inside that repo
//   3. Go to Jenkins -> Manage Jenkins -> Configure System -> Global Pipeline Libraries
//      and point it at your repo
//   4. At the top of any Jenkinsfile add: @Library('cicd-shared-lib') _
//      then just call cicdUtils.detectBuildCommand() etc.

// figures out how to build the project based on what files exist
// add a new entry here if you need to support another stack
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

// same idea but for tests - picks the right runner automatically
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

// runs a linter based on what stack the repo uses
// the || true at the end means lint warnings won't fail the build
// change that if you want to enforce clean code strictly
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

// runs envsubst on all YAML files in srcDir and writes them to destDir
// this is how we inject the image tag and namespace into the K8s manifests
// without needing Helm
//
// @param srcDir   where your template YAML files live (default: k8s/)
// @param destDir  where to write the rendered files
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

// applies the rendered manifests to the cluster
// pass dryRun=true to validate without actually deploying anything
//
// @param renderedDir   folder with the processed YAML files
// @param namespace     which K8s namespace to deploy into
// @param kubeconfigVar env var name that holds the kubeconfig path
// @param dryRun        set to true to do a dry run
def applyManifests(String renderedDir, String namespace, String kubeconfigVar = 'KUBECONFIG', Boolean dryRun = false) {
    def dryRunFlag = dryRun ? '--dry-run=client' : ''
    sh """
        kubectl apply -f ${renderedDir}/ \
            --namespace=${namespace} \
            ${dryRunFlag}
    """
}

// blocks until the deployment finishes rolling out or the timeout hits
// if it times out, the stage fails and Jenkins marks the build as failed
//
// @param deploymentName  name of the K8s Deployment resource
// @param namespace       namespace it lives in
// @param timeoutMinutes  how long to wait
def waitForRollout(String deploymentName, String namespace, Integer timeoutMinutes = 5) {
    sh """
        kubectl rollout status deployment/${deploymentName} \
            --namespace=${namespace} \
            --timeout=${timeoutMinutes}m
    """
}

// logs into the registry and pushes the image
// credentials come from Jenkins credential store, never hardcoded
//
// @param localImage  the image name as it was built
// @param registry    registry host (e.g. docker.io)
// @param credId      Jenkins credential ID for the registry login
def pushDockerImage(String localImage, String registry, String credId = 'docker-registry-credentials') {
    withCredentials([usernamePassword(credentialsId: credId, usernameVariable: 'REG_USER', passwordVariable: 'REG_PASS')]) {
        sh """
            echo \${REG_PASS} | docker login ${registry} -u \${REG_USER} --password-stdin
            docker push ${localImage}
        """
    }
}

// sends a Slack message - wraps in try/catch so a missing credential
// doesn't fail the whole build
//
// @param message  the message text (supports Slack markdown)
// @param color    color of the attachment bar on the left
// @param channel  which channel to post to
// @param credId   Jenkins credential ID holding the webhook URL
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

// maps branch name to deployment environment
// main/master goes to prod, release/* goes to staging, everything else to dev
// this is called automatically so you don't have to set DEPLOY_ENV manually
def envFromBranch(String branch) {
    if (branch ==~ /^(main|master)$/)       return 'prod'
    if (branch ==~ /^release\/.+$/)         return 'staging'
    return 'dev'
}
