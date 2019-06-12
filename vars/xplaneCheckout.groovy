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

        sh(returnStatus: true, returnStdout: true, script: 'git rm -r --cached "Resources/mobile data/"')
        for(String toClean : ['Resources/mobile data/CIFP/']) {
            if(utils.shellIsSh(platform)) {
                sh(returnStatus: true, script: "rm -rf \"${toClean}\"")
            } else {
                bat(returnStatus: true, script: "rmdir /Q /S \"${toClean}\"")
            }
        }

        if(platform == 'Linux' || platform.contains('Bash')) {
            sshagent(['tylers-ssh']) {
                sh "git branch"
                try {
                    sh(returnStdout: true, script: "git reset --hard")
                } catch(e) { }
                sh(returnStdout: true, script: "git fetch --tags")
                sh "git checkout ${branchName}"
                sh(returnStatus: true, script: "git pull") // If we're in detached HEAD mode, pull will fail
            }
        } else {
            checkout(
                    [$class: 'GitSCM', branches: [[name: branchName]],
                     userRemoteConfigs: [[credentialsId: 'tylers-ssh', url: repo]]]
            )
        }

        if(fileExists('scripts/setup_submodules.sh')) {
            if(utils.shellIsSh(platform)) {
                dir(checkoutDir + 'scripts') {
                    sshagent(['tylers-ssh']) {
                        sh './setup_submodules.sh'
                    }
                }
            } else { // Gotta recreate the damn setup_submodules.sh script on Windows
                String remote = bat(returnStdout: true, script: "git remote get-url --push origin").trim().split("\r?\n")[1]
                String remoteParent = remote.substring(0, remote.lastIndexOf('/') + 1)
                bat "git config --file=.gitmodules \"submodule.Resources\\dlls\\64\\cef.url\" ${remoteParent}cef.git"
                bat "git config --file=.gitmodules \"submodule.Resources\\default scenery\\default atc.url\" ${remoteParent}atc_res.git"
                bat "git config --file=.gitmodules \"submodule.Resources\\default scenery\\default apt dat.url\" ${remoteParent}default_apts.git"
                bat "git config --file=.gitmodules \"submodule.Custom\\ Scenery\\Global Airports.url\" ${remoteParent}global_apts.git"
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
        }

        utils.shell(script: 'git reset --hard', platform: platform, returnStatus: true)
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

