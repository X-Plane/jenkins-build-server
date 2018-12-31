def environment = [:]
environment['branch_name'] = 'master'
environment['send_emails'] = 'true'
environment['build_windows'] = utils.isWindows(platform)
environment['build_linux'] = utils.isLinux(platform)
environment['build_mac'] = utils.isMac(platform)
utils.setEnvironment(environment, this.&notify, this.steps)

stage('Checkout')   { doCheckout(platform) }
stage('Update')     { updateFraudPreventionData(platform) }


String getCheckoutDir(platform) {
    return utils.chooseByPlatformNixWin('/jenkins/wordpress/', 'C:\\jenkins\\wordpress\\', platform)
}

def doCheckout(String platform) {
    try {
        xplaneCheckout('master', getCheckoutDir(platform), platform, 'ssh://tyler@dev.x-plane.com/admin/git-xplane/wordpress.git')
    } catch(e) {
        notifyBrokenCheckout(utils.&sendEmail, 'Fraud prevention updater', 'master', platform, e)
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



