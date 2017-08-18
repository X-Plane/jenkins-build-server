//stage('Checkout') { run(this.&doCheckout) }
stage('Test')     { run(this.&testFunnel) }


def run(Closure c) {
    def closure = c
    node('windows') { closure('Windows') }
}

def doCheckout(String platform) {
    dir(getCheckoutDir()) {
        try {
            checkout(
                    [$class: 'GitSCM', branches: [[name: 'origin/master']],
                     doGenerateSubmoduleConfigurations: false,
                     extensions: [
                             //[$class: 'CleanCheckout'],
                             //[$class: 'CleanBeforeCheckout'],
                             [$class: 'BuildChooserSetting', buildChooser: [$class: 'AncestryBuildChooser', ancestorCommitSha1: '', maximumAgeInDays: 21]]
                     ],
                     submoduleCfg: [],
                     userRemoteConfigs:  [[credentialsId: 'tylers-ssh', url: 'ssh://tyler@dev.x-plane.com/admin/git-xplane/website.git']]]
            )
            def commit_id = getCommitId()
            echo "Building commit ${commit_id} on " + platform
        } catch(e) {
            currentBuild.result = "FAILED"
            notifyBuild('Sales funnel Git checkout is broken on ' + platform,
                    'Sales funnel Git checkout failed. We will be unable to continuously check the web site until this is fixed.',
                    e.toString(),
                    'tyler@x-plane.com')
            throw e
        }
    }
}

def getCheckoutDir() {
    def nix = isUnix()
    return (nix ? '/jenkins/' : 'C:\\jenkins\\') + 'website'+ (nix ? '/' : '\\')
}

def getCommitId() {
    dir(getCheckoutDir()) {
        if(isUnix()) {
            return sh(returnStdout: true, script: "git rev-parse HEAD").trim()
        } else {
            return bat(returnStdout: true, script: "git rev-parse HEAD").trim().split("\r?\n")[1]
        }
    }
}

def testFunnel(String platform) {
    dir(getCheckoutDir()) {
        try {
            if(platform == 'Windows') {
                bat "virtualenv env"
                bat "env\\Scripts\\activate"
                bat "pip install -r package_requirements.txt"
                bat "behave --tags=automated"
            } else {
                sh "virtualenv env"
                sh "source env/bin/activate"
                sh "pip install -r package_requirements.txt"
                sh "behave --tags=automated"
            }
        } catch(e) {
            notifyBuild("Web site test failed", "Check the logs.", e.toString(), "tyler@x-plane.com")
            throw e
        }
    }
}

def notifyBuild(String subj, String msg, String errorMsg, String recipient=NULL) { // null recipient means we'll send to the most likely suspects
    body = """${msg}
    
The error was: ${errorMsg}
        
Build URL: ${BUILD_URL}
Console Log: ${BUILD_URL}console
"""
    emailext attachLog: true,
            body: body,
            subject: subj,
            to: recipient ? recipient : emailextrecipients([
                    [$class: 'CulpritsRecipientProvider'],
                    [$class: 'RequesterRecipientProvider']
            ])
}


