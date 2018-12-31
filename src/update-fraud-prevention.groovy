stage('Checkout')   { doCheckout(platform) }
stage('Update')     { updateFraudPreventionData(platform) }


String getCheckoutDir(platform) {
    return utils.chooseByPlatformNixWin('/jenkins/wordpress/', 'C:\\jenkins\\wordpress\\', platform)
}

def doCheckout(String platform) {
    try {
        xplaneCheckout('master', getCheckoutDir(platform), platform, 'ssh://tyler@dev.x-plane.com/admin/git-xplane/wordpress.git')
    } catch(e) {
        currentBuild.result = "FAILED"
        notifyBuild('Fraud prevention Git checkout is broken on ' + platform, e.toString(), 'tyler@x-plane.com')
        throw e
    }
}

def updateFraudPreventionData(String platform) {
    dir(getCheckoutDir(platform)) {
        dir('scripts/fraud-prevention') {
            setUpPython3VirtualEnvironment(utils, platform)
            String binDir = utils.chooseByPlatformNixWin('bin', 'Scripts', platform)
            utils.chooseShell("env/${binDir}/python3 update_fraud_prevention_data.py --commit --push", platform)
        }
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

def notifyBuild(String subj, String msg, String errorMsg, String recipient=NULL) { // null recipient means we'll send to the most likely suspects
    body = """${msg}
    
The error was: ${errorMsg}

Download the screenshots: ${BUILD_URL}artifact/*zip*/archive.zip
        
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


