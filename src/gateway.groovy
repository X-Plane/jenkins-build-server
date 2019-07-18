def environment = [:]
environment['branch_name'] = branch_name
environment['send_emails'] = 'false'
environment['directory_suffix'] = ''
environment['build_windows'] = 'false'
environment['build_mac'] = 'false'
environment['build_linux'] = 'true'
environment['build_all_apps'] = 'false'
environment['build_type'] = ''
utils.setEnvironment(environment, this.&notify)
platform = 'macOS'
nodeType = 'mac'

wantSeleniumTests = test_type == 'complete' || test_type == 'cucumber_selenium'
wantApiTests = test_type == 'complete' || test_type == 'api'
slackLogLink = "| <${BUILD_URL}parsed_console/|Parsed Console Log>"
pm2 = "JENKINS_NODE_COOKIE=dontKillMe node_modules/.bin/pm2"

//--------------------------------------------------------------------------------------------------------------------------------
// RUN THE TESTS
// This is where the magic happens.
//--------------------------------------------------------------------------------------------------------------------------------
try {
    stage('Checkout') { node(nodeType) { timeout(60 * 1) { doCheckout() } } }
    stage('Setup')    { node(nodeType) { timeout(60 * 1) { setup() } } }
    stage('Test')     { node(nodeType) { timeout(60 * 2) { doTest() } } }
    stage('Notify')   { notifySlackComplete() }
} finally {
    node(nodeType) {
        teardown()
        String parseRulesUrl = 'https://raw.githubusercontent.com/X-Plane/jenkins-build-server/master/log-parser-builds.txt'
        utils.chooseShellByPlatformNixWin("curl ${parseRulesUrl} -O", "C:\\msys64\\usr\\bin\\curl.exe ${parseRulesUrl} -O", platform)
        step([$class: 'LogParserPublisher', failBuildOnError: false, parsingRulesPath: "${pwd()}/log-parser-builds.txt", useProjectRule: false])
    }
}


//--------------------------------------------------------------------------------------------------------------------------------
// IMPLEMENTATION
//--------------------------------------------------------------------------------------------------------------------------------
def doCheckout() {
    try {
        checkout(
                [$class: 'GitSCM', branches: [[name: branch_name]],
                 userRemoteConfigs: [[credentialsId: 'tylers-ssh', url: 'ssh://tyler@dev.x-plane.com/admin/git-xplane/gateway.git']]]
        )
    } catch(e) {
        slackBuildInitiatorFailure("Gateway checkout failed for `${branch_name}` ${slackLogLink}")
        throw e
    }
}

def setup() {
    withEnv(["NODE_ENV=test"]) {
        dir('.') {
            utils.shell('npm install')
            utils.shell('node_modules/.bin/grunt build')
            utils.shell("$pm2 start app.js")
        }
    }
}

def teardown() {
    withEnv(["NODE_ENV=test"]) {
        dir('.') {
            utils.shell("$pm2 stop app.js")
        }
    }
}

def doTest() {
    withEnv(["NODE_ENV=test"]) {
        dir('.') { // Run everything from the workspace root, where we checked out
            if(wantSeleniumTests) {
                try {
                    runCucumberTests()
                } catch(e) {
                    slackBuildInitiatorFailure("Gateway Cucumber tests failed on `${branch_name}` ${slackLogLink}")
                    error('Cucumber tests failed')
                }
            }

            if(wantApiTests) {
                try {
                    runApiTests()
                } catch(e) {
                    slackBuildInitiatorFailure("Gateway API tests failed on `${branch_name}` ${slackLogLink}")
                    error('API tests failed')
                }
            }
        }
    }
}

def runCucumberTests() {
    utils.shell('node_modules/.bin/selenium-standalone install --config=selenium-config.js')
    try {
        utils.shell("$pm2 start scripts/run_selenium.sh")
        tag_arg = specify_tag ? "-t=${specify_tag}" : ''
        utils.shell("node_modules/.bin/cucumber.js test/features -r test/features/ ${tag_arg}")
    } finally {
        utils.shell("$pm2 stop scripts/run_selenium.sh")
    }
}

def runApiTests() {
    dir('scripts') {
        setUpPython3VirtualEnvironment(utils, platform)
        utils.shell('env/bin/python3 test_api.py')
    }
}

def notifySlackComplete() {
    String tests = ''
    if(test_type == 'complete') {
        tests = 'all tests'
    } else if(wantApiTests) {
        tests = 'API tests'
    } else if(wantSeleniumTests) {
        tests = 'Cucumber tests'
    }
    slackBuildInitiatorSuccess("Gateway ${tests} of `${branch_name}` passed ${slackLogLink}")
}

