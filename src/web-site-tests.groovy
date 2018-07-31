stage('Checkout') { run(this.&doCheckout) }
try {
    stage('Test')     { run(this.&testFunnel) }
} finally { // we want to archive regardless of whether the tests passed
    stage('Archive')  { run(this.&doArchive) }
}


def run(Closure c) {
    def closure = c
    node('windows') { closure('Windows') }
}

def doCheckout(String platform) {
    try {
        xplaneCheckout('master', getCheckoutDir(), platform, 'ssh://tyler@dev.x-plane.com/admin/git-xplane/website.git')
    } catch(e) {
        currentBuild.result = "FAILED"
        notifyBuild('Sales funnel Git checkout is broken on ' + platform,
                'Sales funnel Git checkout failed. We will be unable to continuously check the web site until this is fixed.',
                e.toString(),
                'tyler@x-plane.com')
        throw e
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
                bat "behave --tags=${tag}"
            } else {
                sh "virtualenv env"
                sh "source env/bin/activate"
                sh "pip install -r package_requirements.txt"
                sh "behave --tags=${tag}"
            }
        } catch(e) {
            notifyBuild("Web site test failed", "Check the logs.", e.toString(), "tyler@x-plane.com")
            throw e
        }
    }
}

def doArchive(String platform) {
    def images = []
    for(def file : findFiles(glob: '*.png')) {
        images.push(file.name)
    }
    if(images) {
        archiveArtifacts artifacts: images.join(', '), fingerprint: true, onlyIfSuccessful: false
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


