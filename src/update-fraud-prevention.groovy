def environment = [:]
environment['branch_name'] = 'master'
environment['send_emails'] = 'true'
utils.setEnvironment(environment, this.&notify, this.steps, platform)

String nodeType = utils.isWindows(platform) ? 'windows' : (utils.isMac(platform) ? 'mac' : 'linux')

stage('Checkout')   { node(nodeType) { doCheckout(platform) } }
stage('Update')     { node(nodeType) { updateFraudPreventionData(platform) } }


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
        dir('scripts') {
            setUpPython3VirtualEnvironment(utils, platform)
            dir('fraud-prevention') {
                String binDir = utils.chooseByPlatformNixWin('bin', 'Scripts', platform)
                withCredentials([usernamePassword(credentialsId: 'customer-io', usernameVariable: 'CUSTOMER_IO_EMAIL', passwordVariable: 'CUSTOMER_IO_PASSWORD')]) {
                    sshagent(['tylers-ssh']) {
                        utils.chooseShell("../env/${binDir}/python3 update_fraud_prevention_data.py --commit --push --verbose", platform)
                    }
                }
            }
        }
    }
}



