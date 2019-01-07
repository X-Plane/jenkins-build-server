def environment = [:]
environment['branch_name'] = 'master'
environment['send_emails'] = 'true'
utils.setEnvironment(environment, this.&notify, this.steps, platform)

String nodeType = utils.isWindows(platform) ? 'windows' : (utils.isMac(platform) ? 'mac' : 'linux')

stage('Checkout')     { node(nodeType) { doCheckout(platform) } }
try {
    stage('Test') {
        node(nodeType) {
            try {
                testFunnel(platform)
            } catch(e) { // Give it a second try in case of temporary connectivity issues
                echo "Failed the first time... let's retry before annoying Tyler..."
                testFunnel(platform)
            }
        }
    }
} catch(e) {
    slackSend(
            color: 'danger',
            message: "Hey <@UAG6R8LHJ>, the web site test of ${tag} failed | <${BUILD_URL}console|Console Log> | <${BUILD_URL}|Build Info>")
    if(tag.contains('Critical')) {
        notifyPagerDuty("Web site test of ${tag} failed")
    }
    throw e
}
finally { // we want to archive regardless of whether the tests passed
    stage('Archive')  { node(nodeType) { doArchive(platform) } }
}

String getCheckoutDir(platform) {
    return utils.chooseByPlatformNixWin('/jenkins/website/', 'C:\\jenkins\\website\\', platform)
}

def doCheckout(String platform) {
    try {
        dir(getCheckoutDir(platform)) {
            utils.nukeIfExist(['*.png'], platform)
        }

        xplaneCheckout('master', getCheckoutDir(platform), platform, 'ssh://tyler@dev.x-plane.com/admin/git-xplane/website.git')
    } catch(e) {
        notifyBrokenCheckout(utils.&sendEmail, 'Sales funnel', 'master', platform, e)
        throw e
    }
}


def getCommitId() {
    dir(getCheckoutDir(platform)) {
        if(isUnix()) {
            return sh(returnStdout: true, script: "git rev-parse HEAD").trim()
        } else {
            return bat(returnStdout: true, script: "git rev-parse HEAD").trim().split("\r?\n")[1]
        }
    }
}

def testFunnel(String platform) {
    dir(getCheckoutDir(platform)) {
        setUpPython3VirtualEnvironment(utils, platform)
        String binDir = utils.chooseByPlatformNixWin('bin', 'Scripts')
        utils.chooseShell("env/${binDir}/behave --tags=${tag}", platform)
    }
}

def doArchive(String platform) {
    dir(getCheckoutDir(platform)) {
        def images = []
        for(def file : findFiles(glob: '*.png')) {
            images.push(file.name)
        }
        if(images) {
            archiveArtifacts artifacts: images.join(', '), fingerprint: true, onlyIfSuccessful: false
        }
    }
}

def notifyPagerDuty(String title) {
    API_KEY = 'a5fc7b93193044118fc1b5be9c7ef082'
    pagerduty(
            resolve: false,
            serviceKey: API_KEY,
            incDescription: title,
            incDetail: "${BUILD_URL}console"
    )
}
