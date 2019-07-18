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
platform = 'Linux'
nodeType = 'linux'

runSeleniumTests = test_type == 'complete' || test_type == 'cucumber_selenium'
runApiTests = test_type == 'complete' || test_type == 'api'
slackLogLink = "| <${BUILD_URL}parsed_console/|Parsed Console Log>"
pm2 = "JENKINS_NODE_COOKIE=dontKillMe node_modules/.bin/pm2"

//--------------------------------------------------------------------------------------------------------------------------------
// RUN THE TESTS
// This is where the magic happens.
//--------------------------------------------------------------------------------------------------------------------------------
try {
    stage('Checkout') { node(nodeType) { timeout(60 * 1) { doCheckout() } } }
    stage('Test')     { node(nodeType) { timeout(60 * 2) { doTest() } } }
    stage('Notify')   { utils.replyToTrigger("SUCCESS!\n\nThe automated build of commit ${branch_name} succeeded on ${platform}.") }
} finally {
    node(nodeType) {
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
                [$class: 'GitSCM', branches: [[name: branchName]],
                 userRemoteConfigs: [[credentialsId: 'tylers-ssh', url: repo]]]
        )
    } catch(e) {
        slackBuildInitiatorFailure("Gateway checkout failed for `${branch_name}` ${slackLogLink}")
        throw e
    }
}

def doTest() {
    withEnv(["NODE_ENV=test"]) {
        utils.shell('npm install')
        utils.shell('node_modules/.bin/grunt build')

        try {
            utils.shell("$pm2 start app.js")

            try {
                runCucumberTests()
            } catch(e) {
                currentBuild.result = 'FAIL'
                slackBuildInitiatorFailure("Gateway Cucumber tests failed on `${branch_name}` ${slackLogLink}")
            }

            try {
                runApiTests()
            } catch(e) {
                currentBuild.result = 'FAIL'
                slackBuildInitiatorFailure("Gateway Cucumber tests failed on `${branch_name}` ${slackLogLink}")
            }
        } finally {
            utils.shell("$pm2 stop app.js")
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
    setUpPython3VirtualEnvironment(utils, platform)
    utils.shell('env/bin/python3 scripts/test_api.py')
}


