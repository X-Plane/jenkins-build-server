String nodeType = utils.isWindows(platform) ? 'windows' : (utils.isMac(platform) ? 'mac' : 'linux')

def environment = [:]
environment['branch_name'] = 'master'
environment['send_emails'] = 'true'
environment['build_windows'] = utils.isWindows(platform) ? 'true' : 'false'
environment['build_mac'] = utils.isMac(platform) ? 'true' : 'false'
environment['build_linux'] = utils.isLinux(platform) ? 'true' : 'false'
utils.setEnvironment(environment, this.&notify, this.steps)


stage('Checkout')   { node(nodeType) { doCheckout(platform) } }
stage('Update')     { node(nodeType) { updateFraudPreventionData(platform) } }
stage('Deploy')     { node(nodeType) { deployToProduction(platform) } }


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
                        String args = '--commit --verbose'
                        if(utils.toRealBool(export_exists)) {
                            args += ' --export_exists'
                        }
                        utils.chooseShell("../env/${binDir}/python3 update_fraud_prevention_data.py ${args}", platform)
                    }
                }
            }
        }
    }
}

def deployToProduction(String platform) {
    dir(getCheckoutDir(platform)) {
        sshagent(['wpengine-ssh']) {
            utils.chooseShell('git push git@git.wpengine.com:production/xplanedotcom.git', platform)
        }
    }
}



