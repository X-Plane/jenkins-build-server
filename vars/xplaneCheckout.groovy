def call(String branchName='', String checkoutDir='', String platform='', String repo='ssh://tyler@dev.x-plane.com/admin/git-xplane/design.git') {
    if(!fileExists("${checkoutDir}.git")) {
        sshagent(['tylers-ssh']) {
            if(isUnix() || platform.contains('Bash')) {
                checkoutDirNix = checkoutDir.replace('C:\\', '/c/').replace('D:\\', '/d/').replace('\\', '/').replace(' ', '\\ ')
                sh "git clone ${repo} ${checkoutDirNix}"
            } else {
                bat "git clone ${repo} ${checkoutDir}"
            }
        }
    }

    dir(checkoutDir) {
        echo "Checking out ${branchName} on ${platform}"

        for(String toClean : ["Resources/default scenery/default apt dat/", "Custom Scenery/Global Airports/"]) {
            if(utils.shellIsSh(platform)) {
                sh(returnStatus: true, script: "rm -rf \"${toClean}\"")
            } else {
                bat(returnStatus: true, script: "rmdir /Q /S \"${toClean}\"")
            }
        }

        if(platform == 'Linux' || platform.contains('Bash')) {
            sshagent(['tylers-ssh']) {
                sh "git branch"
                sh(returnStdout: true, script: "git fetch --tags")
                sh(returnStdout: true, script: "git reset --hard")
                sh "git checkout ${branchName}"
                sh(returnStatus: true, script: "git pull") // If we're in detached HEAD mode, pull will fail
            }
        } else {
            checkout(
                    [$class: 'GitSCM', branches: [[name: branchName]],
                     userRemoteConfigs: [[credentialsId: 'tylers-ssh', url: repo]]]
            )
        }

        if(utils.shellIsSh(platform)) {
            sh(returnStatus: true, script: 'git rm --cached Resources/mobile data/CIFP/')
            dir(checkoutDir + 'scripts') {
                if(fileExists('setup_submodules.sh')) {
                    sshagent(['tylers-ssh']) {
                        sh './setup_submodules.sh'
                    }
                }
            }
        } else { // Gotta recreate the damn setup_submodules.sh script on Windows
            String remote = bat(returnStdout: true, script: "git remote get-url --push origin").trim().split("\r?\n")[1]
            String remoteParent = remote.substring(0, remote.lastIndexOf('/') + 1)
            bat "git config --file=.gitmodules \"submodule.Resources\\dlls\\64\\cef.url\" ${remoteParent}cef.git"
            bat "git config --file=.gitmodules \"submodule.Resources\\default scenery\\default atc.url\" ${remoteParent}atc_res.git"
            bat "git config --file=.gitmodules \"submodule.Resources\\default scenery\\default apt dat.url\" ${remoteParent}default_apts.git"
            bat "git config --file=.gitmodules \"submodule.Custom Scenery\\Global Airports.url\" ${remoteParent}global_apts.git"
            // Now do the checkout again, with the proper submodules
            try {
                checkout(
                        [$class: 'GitSCM', branches: [[name: branchName]],
                         doGenerateSubmoduleConfigurations: false,
                         extensions: [
                                 [$class: 'CloneOption', timeout: 120],
                                 [$class: 'SubmoduleOption',
                                       disableSubmodules: false,
                                       parentCredentials: true,
                                       recursiveSubmodules: true]
                         ], submoduleCfg: [],
                         userRemoteConfigs:  [[credentialsId: 'tylers-ssh', url: repo]]]
                )
            } catch(e) {
                checkout(
                        [$class: 'GitSCM', branches: [[name: branchName]],
                         doGenerateSubmoduleConfigurations: false,
                         extensions: [
                                 [$class: 'CloneOption', timeout: 120],
                                 [$class: 'SubmoduleOption',
                                       disableSubmodules: false,
                                       parentCredentials: true,
                                       recursiveSubmodules: true]
                         ], submoduleCfg: [],
                         userRemoteConfigs:  [[credentialsId: 'tylers-ssh', url: repo]]]
                )
            }
        }

        utils.chooseShellByPlatformNixWin('git reset --hard', 'git reset --hard', platform)
        utils.chooseShell('git submodule update --recursive', platform)

        String commitId = ""
        if(utils.shellIsSh(platform)) {
            commitId = sh(returnStdout: true, script: "git rev-parse HEAD").trim()
        } else {
            commitId = bat(returnStdout: true, script: "git rev-parse HEAD").trim().split("\r?\n")[1]
        }
        echo "Checked out commit ${commitId}"
    }
}

