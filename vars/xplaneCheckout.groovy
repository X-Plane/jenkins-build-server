def call(String branchName='', String checkoutDir='', String platform='', String repo='ssh://tyler@dev.x-plane.com/admin/git-xplane/design.git') {
    if(!fileExists("${checkoutDir}.git")) {
        if(isUnix() || platform.contains('Bash')) {
            checkoutDirNix = checkoutDir.replace('C:\\', '/c/').replace('D:\\', '/d/').replace('\\', '/').replace(' ', '\\ ')
            sh "git clone ${repo} ${checkoutDirNix}"
        } else {
            bat "git clone ${repo} ${checkoutDir}"
        }
    }

    dir(checkoutDir) {
        echo "Checking out ${branchName} on ${platform}"
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
                     extensions: [
                             [$class: 'BuildChooserSetting', buildChooser: [$class: 'AncestryBuildChooser', ancestorCommitSha1: '']]
                     ],
                     userRemoteConfigs:  [[credentialsId: 'tylers-ssh', url: repo]]]
            )
        }

        if(utils.shellIsSh(platform) && fileExists('scripts/setup_submodules.sh')) {
            sh(returnStatus: true, script: 'git rm --cached SDK/COMMON/xairnav/src/units/')
            dir(checkoutDir + 'scripts') {
                sshagent(['tylers-ssh']) {
                    sh './setup_submodules.sh'
                }
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

