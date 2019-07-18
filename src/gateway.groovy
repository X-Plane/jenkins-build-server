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
        step([$class: 'LogParserPublisher', failBuildOnError: true, parsingRulesPath: "${pwd()}/log-parser-builds.txt", useProjectRule: false])
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
            utils.shell('npm install', platform)
            utils.shell('node_modules/.bin/grunt build', platform)
            utils.shell("$pm2 stop all", platform)
            utils.shell("$pm2 start app.js", platform)
        }
    }
}

def teardown() {
    withEnv(["NODE_ENV=test"]) {
        dir('.') {
            utils.shell("$pm2 stop all", platform)
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
    utils.shell("$pm2 start scripts/run_selenium.sh", platform)
    sleep(5)  // let Selenium get it together so that it doesn't error out when we run our first test
    utils.shell("$pm2 status", platform)
    lastError = null
    for(def filePath : findFiles(glob: 'test/features/*.feature')) {
        if(!specify_tag || fileContains(filePath, specify_tag)) {
            tag_arg = specify_tag ? "-t=${specify_tag}" : ''
            try {
                utils.shell("node_modules/.bin/cucumber.js ${filePath} -r test/features/ ${tag_arg} --no-colors", platform)
            } catch(e) {
                lastError = e
            }
        }
    }

    if(lastError) {
        utils.shell("$pm2 status", platform)
        throw lastError
    }
}

def fileContains(String filePath, String search) {
    String completeFile = readFile(filePath).replace('\r\n', '\n').replace('\r', '\n') // Turn Windows-style line feeds into plain \n
    return completeFile.contains(search)
}

def runApiTests() {
    dir('scripts') {
        setUpPython3VirtualEnvironment(utils, platform)
        utils.shell('env/bin/python3 test_api.py --no-color', platform)
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

