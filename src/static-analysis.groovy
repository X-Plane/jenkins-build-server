def setEnvironment() {
    env.branch = "email-triggered"
    env.directory_suffix = "static-analysis"
    env.subject = params.pmt_subject
    env.from = params.pmt_from
}

try {
    setEnvironment()
    stage('Respond')                       { replyToTrigger('Analysis started.\n\nThe static analysis of commit ' + env.subject + ' is in progress.') }
    stage('Ping Machines')                 { runOnMac(this.&ping) }
    stage('Checkout')                      { runOnMac(this.&doCheckout) }
    stage('Analyze')                       { runOnMac(this.&doAnalysis) }
    stage('Archive')                       { runOnMac(this.&doArchive) }
    stage('Notify')                        { replyToTrigger('SUCCESS!\n\nThe static analysis of commit ' + env.subject + ' succeeded.') }
} catch(e) {
    replyToTrigger('The static analysis of commit ' + env.subject + ' failed.', e.toString())
    throw e
}


def runOnMac(Closure c) {
    node('mac') { c('macOS') }
}

def doCheckout(String platform) {
    setEnvironment()
    dir(getCheckoutDir()) {
        try {
            def branch = env.subject.trim() // Subject should *just* contain the commit SHA
            echo "Checking out ${branch} on ${platform}"
            checkout([$class: 'GitSCM', branches: [[name: branch]], doGenerateSubmoduleConfigurations: false, extensions: [], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'tylers-ssh', url: 'ssh://tyler@dev.x-plane.com/admin/git-xplane/design.git']]])
            def commit_id = getCommitId()
            assert commit_id == branch : "We didn't check out the commit you asked for."
            echo "Building commit ${commit_id} on " + platform
        } catch(e) {
            currentBuild.result = "FAILED"
            notifyBuild('Jenkins Git checkout is broken on ' + platform + ' [' + env.branch + ']',
                    platform + ' Git checkout failed on branch ' + env.branch + '. We will be unable to do continuous builds until this is fixed.',
                    e.toString(),
                    'tyler@x-plane.com')
            throw e
        }
    }
}

def getCheckoutDir() {
    def nix = isUnix()
    return (nix ? '/jenkins/' : 'D:\\jenkins\\') + 'design-' + env.directory_suffix + (nix ? '/' : '\\')
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

def doAnalysis(String platform) {
    setEnvironment()
    dir(getCheckoutDir()) {
        sh 'xcodebuild -scheme "X-Plane Debug" -project design_xcode4.xcodeproj clean'
        // xcodebuild returns 1 in the event of any issues found... obviously that still means the *analysis* went correctly
        sh(returnStatus: true, script: 'xcodebuild -scheme "X-Plane Debug" -project design_xcode4.xcodeproj analyze > analysis.txt')
    }
}

def doArchive(String platform) {
    setEnvironment()
    try {
        def checkout_dir = getCheckoutDir()
        dir(checkout_dir) {
            def dropbox_path = getArchiveDirAndEnsureItExists()
            echo "Copying files from ${checkout_dir} to ${dropbox_path}"

            def file_pattern = "analysis.txt"
            archiveArtifacts artifacts: file_pattern, fingerprint: true, onlyIfSuccessful: true

            try {
                sh "rm -R ${dropbox_path}${file_pattern}"
            } catch(e) { } // if no old versions are lying around, no problem

            sh "mv ${file_pattern} ${dropbox_path}"
        }
    } catch (e) {
        notifyFailedArchive(platform, e)
    }
}

def getArchiveDirAndEnsureItExists() {
    def commit_id = getCommitId()
    def out = ""
    if(isUnix()) {
        out = "/jenkins/Dropbox/X-Plane\\ non-code/Jenkins\\ archive/${env.directory_suffix}-${env.branch}-${commit_id}/"
        try {
            sh "mkdir ${out}"
        } catch(e) { } // ignore errors if it already exists
    } else {
        out = "D:\\Docs\\Dropbox\\X-Plane non-code\\Jenkins archive\\${env.directory_suffix}-${env.branch}-${commit_id}\\"
        try {
            bat "mkdir \"${out}\""
        } catch(e) { } // ignore errors if it already exists
    }
    return out
}

def notifyFailedArchive(String platform, Exception e) {
    setEnvironment()
    notifyBuild('Jenkins archive step broken on ' + platform + ' [' + env.branch + ']',
            'Archive step failed on ' + platform + ', branch ' + env.branch + '. We will be unable to archive builds until this is fixed.',
            e.toString(),
            'tyler@x-plane.com')
    throw e
}

def replyToTrigger(String msg, String errorMsg='') {
    setEnvironment()
    notifyBuild("Re: " + env.subject, msg, errorMsg, env.from)
}

def notifyBuild(String subj, String msg, String errorMsg, String recipient=NULL) { // null recipient means we'll send to the most likely suspects
    setEnvironment()
    def summary = errorMsg.isEmpty() ?
            "Download the analysis: ${BUILD_URL}artifact/analysis.txt" :
            "The error was: ${errorMsg}"
    body = """${msg}

${summary}
        
Build URL: ${BUILD_URL}
Console Log: ${BUILD_URL}console
"""
    emailext attachLog: true,
            body: body,
            subject: subj,
            to: recipient ? recipient : env.from
}

def ping(String platform) {
    echo "${platform} online"
}

